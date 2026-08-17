package io.pzstorm.storm.metrics;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.prometheus.metrics.core.metrics.Histogram;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.iso.IsoChunk;
import zombie.network.ClientChunkRequest;
import zombie.network.ClientServerMap;
import zombie.network.GameServer;
import zombie.network.PlayerDownloadServer;
import zombie.network.ServerMap;
import zombie.vehicles.BaseVehicle;

/**
 * Per-peer chunk-streaming backlog, polled every server tick from {@link
 * io.pzstorm.storm.advice.servertick.ServerTickAdvice}.
 *
 * <p>These gauges exist to make the "player outran the stream" failure visible. The vanilla supply
 * side is far narrower than it looks: {@code GameServer} calls {@link
 * PlayerDownloadServer#update()} once per connection per tick, and that call dispatches <em>at most
 * one</em> {@code ClientChunkRequest} — capped at 20 chunks by {@code
 * ClientChunkRequest.isChunksFilled()} — and only when the connection's single worker thread has
 * finished the previous one. The demand side has no such cap: {@code WorldStreamer.updateMain()}
 * packs every chunk the client currently wants into one {@code RequestZipList}, which {@code
 * RequestZipListPacket.parse} splits into as many 20-chunk requests as it takes and appends to
 * {@code ccrWaiting}. A driving player generates chunk demand faster than 20-per-tick-per-player
 * drains it, so {@code ccrWaiting} grows and delivery latency climbs until they stop.
 *
 * <p>Nothing in vanilla or Storm reported that backlog before this class: {@code
 * pz_chunk_load_call_duration_seconds} times {@code doLoadGridsquare} and {@code
 * pz_chunk_save_call_duration_seconds} times {@code IsoChunk.Save(boolean)} — both are hydration /
 * unload costs, neither is on the client-download path. {@link #WORKER_SAMPLES} is the throughput
 * signal: a peer whose worker samples mostly {@code busy} is limited by compression + disk + wire,
 * one that samples mostly {@code ready} with a non-zero backlog is limited by the one-request-per-
 * tick dispatch rule.
 *
 * <p>Sampling only reads: {@link PlayerDownloadServer#getWaitingRequests()}, the {@code ccrWaiting}
 * list, and the worker's {@code ready} flag. It never mutates game state and never takes a game
 * lock. {@code ccrWaiting} is a plain {@code ArrayList} that the worker thread appends to from
 * {@code sendArray} while the main thread drains it, so the chunk-count walk is index-bounded and
 * fenced — a concurrent structural change costs us one imprecise sample, never an exception on the
 * tick path.
 */
public final class ChunkStreamMetrics {

    /**
     * How far ahead {@link #runwayTiles} marches before it gives up and reports the cap. Eight
     * cells is 512 tiles, over thirty seconds of road at highway speed, so a driver who reads the
     * cap is not the one to worry about. Bounding it matters more than the exact number: without a
     * ceiling a clear road across an already-hydrated region would walk the whole map every tick.
     */
    private static final int RUNWAY_HORIZON_CELLS = 8;

    private static final float RUNWAY_HORIZON_TILES = RUNWAY_HORIZON_CELLS * 64.0f;

    /**
     * A quarter cell. Short enough that no axis-aligned ray can jump a 64-tile cell, long enough
     * that the whole march is 32 samples, most of which repeat the previous cell and cost a
     * compare.
     */
    private static final float RUNWAY_STEP_TILES = 16.0f;

    /**
     * Speed below which the velocity vector is too small to give a trustworthy heading, in tiles
     * per second. A parked car still jitters by a hair, and normalising that noise would point the
     * march in a random direction and report a runway for a road the player is not on.
     */
    private static final float RUNWAY_MIN_SPEED = 0.5f;

    private static final Gauge BACKLOG_REQUESTS =
            Gauge.builder()
                    .name("storm_chunk_stream_backlog_requests")
                    .help(
                            "ClientChunkRequests queued in PlayerDownloadServer.ccrWaiting for one"
                                    + " peer. The server dispatches at most one of these per server"
                                    + " tick per connection, and only while that connection's worker"
                                    + " thread is idle, so this is the peer's chunk-delivery backlog"
                                    + " measured in dispatch slots. Sustained non-zero means the client"
                                    + " is asking for chunks faster than the one-per-tick rule can"
                                    + " serve them. Excludes requests whose chunks have all been"
                                    + " drained by cancellation or dedupe, which would otherwise"
                                    + " read as phantom backlog until the queue reaps them.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Gauge BACKLOG_CHUNKS =
            Gauge.builder()
                    .name("storm_chunk_stream_backlog_chunks")
                    .help(
                            "Individual chunks queued across every ClientChunkRequest in"
                                    + " PlayerDownloadServer.ccrWaiting for one peer. Each request"
                                    + " holds up to 20 chunks, so this is roughly 20x"
                                    + " storm_chunk_stream_backlog_requests. Divide by the served rate"
                                    + " to get the peer's chunk-delivery lag in seconds.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Gauge BACKLOG_CHUNKS_MAX =
            Gauge.builder()
                    .name("storm_chunk_stream_backlog_chunks_max")
                    .help(
                            "Largest storm_chunk_stream_backlog_chunks across all peers this tick."
                                    + " Unlabelled companion to the per-peer gauge so a dashboard or"
                                    + " alert can watch the worst-off player without a topk over a"
                                    + " high-cardinality series.")
                    .register(StormPrometheus.registry());

    private static final Gauge CELL_HOLES =
            Gauge.builder()
                    .name("storm_chunk_stream_peer_cell_holes")
                    .help(
                            "Cells this peer's ClientServerMap mirror still has flagged unloaded,"
                                    + " summed over the peer's player indices. This is the server's own"
                                    + " copy of the array the client's vehicle brake consults —"
                                    + " BaseVehicle.isInvalidChunkAhead reads ClientServerMap, not the"
                                    + " client's chunk map — and one flag covers a whole 8x8-chunk,"
                                    + " 64x64-tile ServerCell. The server only ever pushes this mirror"
                                    + " on change, so the client's copy can be staler but never fresher:"
                                    + " treat this as a lower bound on what is braking the player. It is"
                                    + " the production-observable half of"
                                    + " storm_client_server_cell_holes, which needs a scrapeable client.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Gauge CELL_HOLES_MAX =
            Gauge.builder()
                    .name("storm_chunk_stream_peer_cell_holes_max")
                    .help(
                            "Largest storm_chunk_stream_peer_cell_holes across all peers this tick."
                                    + " Unlabelled companion so an alert can watch the worst-off player"
                                    + " without a topk over a high-cardinality series.")
                    .register(StormPrometheus.registry());

    private static final Gauge BRAKE_CELLS =
            Gauge.builder()
                    .name("storm_chunk_stream_peer_brake_cells")
                    .help(
                            "Unloaded mirror cells in the 3x3 cell window centred on the peer's own"
                                    + " cell, summed over the peer's player indices. The whole mirror"
                                    + " spans 256x256 tiles or more, but BaseVehicle.isInvalidChunkAhead"
                                    + " only looks 16 tiles ahead, so only cells adjacent to the player"
                                    + " can actually force the brake. Non-zero here while someone is"
                                    + " driving is the stall; storm_chunk_stream_peer_cell_holes can sit"
                                    + " high on distant edge cells that never matter.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Gauge BRAKE_CELLS_MAX =
            Gauge.builder()
                    .name("storm_chunk_stream_peer_brake_cells_max")
                    .help(
                            "Largest storm_chunk_stream_peer_brake_cells across all peers this tick."
                                    + " Unlabelled companion so an alert can watch the worst-off player"
                                    + " without a topk over a high-cardinality series.")
                    .register(StormPrometheus.registry());

    private static final Gauge BRAKE_SECONDS =
            Gauge.builder()
                    .name("storm_chunk_stream_peer_brake_seconds")
                    .help(
                            "How long this peer has continuously had at least one unloaded cell within"
                                    + " brake range, zero once it clears. This is the reported symptom"
                                    + " measured directly: a driver whose cell ahead never loads sees"
                                    + " CarController hold the brake for exactly this long. Backlog and"
                                    + " hole counts say how bad the plumbing is; this says how long a"
                                    + " player actually could not move, so it is the number a fix has to"
                                    + " bring down.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Gauge BRAKE_SECONDS_MAX =
            Gauge.builder()
                    .name("storm_chunk_stream_peer_brake_seconds_max")
                    .help(
                            "Longest storm_chunk_stream_peer_brake_seconds across all peers this tick."
                                    + " Unlabelled companion so an alert can watch the worst-off player"
                                    + " without a topk over a high-cardinality series.")
                    .register(StormPrometheus.registry());

    private static final Gauge RUNWAY_TILES =
            Gauge.builder()
                    .name("storm_chunk_stream_runway_tiles")
                    .help(
                            "Distance in tiles from this peer to the first cell ahead of them that"
                                    + " ServerMap has not hydrated, measured along their vehicle's"
                                    + " velocity vector and capped at "
                                    + RUNWAY_HORIZON_CELLS
                                    + " cells. Every other gauge here reports congestion after the"
                                    + " player has already hit it; this one reports the margin left"
                                    + " before they do. Divide by"
                                    + " storm_chunk_stream_speed_tiles_per_second for seconds of"
                                    + " runway, and compare that against"
                                    + " storm_chunk_hydration_cell_total_seconds — a driver whose"
                                    + " runway is shorter than the time it takes to hydrate one cell"
                                    + " is going to stall no matter how fast the download side runs,"
                                    + " because the world in front of them does not exist yet. Cells"
                                    + " are sampled every "
                                    + (int) RUNWAY_STEP_TILES
                                    + " tiles, so a ray that only clips a cell corner can miss it."
                                    + " Reported as the cap when the peer is stationary or not in a"
                                    + " vehicle, where there is no heading to march along and the"
                                    + " value carries no information — always gate on the speed"
                                    + " series.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Gauge RUNWAY_TILES_MIN =
            Gauge.builder()
                    .name("storm_chunk_stream_runway_tiles_min")
                    .help(
                            "Shortest storm_chunk_stream_runway_tiles across all peers this tick."
                                    + " Unlabelled companion so an alert can watch the peer closest to"
                                    + " driving off the hydrated world without a bottomk over a"
                                    + " high-cardinality series. Sits at the cap while nobody is"
                                    + " driving.")
                    .register(StormPrometheus.registry());

    private static final Gauge SPEED_TILES_PER_SECOND =
            Gauge.builder()
                    .name("storm_chunk_stream_speed_tiles_per_second")
                    .help(
                            "Horizontal speed of the vehicle this peer is in, zero on foot or"
                                    + " parked. One tile is one metre, so 16.7 here is 60 km/h. Read"
                                    + " from BaseVehicle.jniLinearVelocity, which the server does not"
                                    + " simulate but does write from every authorized"
                                    + " VehiclePhysicsPacket, so it tracks the client's own physics at"
                                    + " that packet's 150 ms cadence. Exported raw rather than folded"
                                    + " into a seconds-of-runway gauge so that a parked car divides to"
                                    + " +Inf instead of a made-up number, and so stalls can be split"
                                    + " by how fast the player was actually going.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Gauge WORKER_BUSY =
            Gauge.builder()
                    .name("storm_chunk_stream_worker_busy")
                    .help(
                            "1 when this peer's PlayerDownloadServer worker thread is mid-request"
                                    + " (ready=false) at tick time, 0 when it is idle and able to"
                                    + " accept the next dispatch. While 1, PlayerDownloadServer.update"
                                    + " does nothing at all for this peer — no chunk leaves the queue,"
                                    + " however deep the backlog.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Counter WORKER_SAMPLES =
            Counter.builder()
                    .name("storm_chunk_stream_worker_samples_total")
                    .help(
                            "Per-tick observations of chunk worker threads, by state, summed over"
                                    + " every connected peer. busy = the worker was still"
                                    + " compressing/reading/sending the previous request, so that tick"
                                    + " dispatched nothing for that peer; ready_backlogged = the worker"
                                    + " was free and work was waiting, so the one-request-per-tick rule"
                                    + " is the limiter; ready_idle = the worker was free with an empty"
                                    + " queue (that peer is keeping up). The busy/ready_backlogged"
                                    + " split says whether raising the dispatch rate would help or just"
                                    + " move the queue. Deliberately not labelled by username: this is"
                                    + " cumulative, so a username label would grow with lifetime unique"
                                    + " logins rather than concurrent players — use"
                                    + " storm_chunk_stream_worker_busy for the per-peer view.")
                    .labelNames("state")
                    .register(StormPrometheus.registry());

    private static final GaugeWithCallback WORKER_THREADS =
            GaugeWithCallback.builder()
                    .name("storm_chunk_stream_worker_threads")
                    .help(
                            "Live threads named PlayerDownloadServer*. Vanilla starts one per"
                                    + " connection in the PlayerDownloadServer constructor and only"
                                    + " joins it in destroy(), so this should track the connection"
                                    + " count; a value that climbs past it and never falls is the"
                                    + " known worker-thread leak. Counted at scrape time rather than"
                                    + " per tick — the root-ThreadGroup walk allocates, and once every"
                                    + " 15s is plenty for a leak that accrues over hours.")
                    .callback(callback -> callback.call(countWorkerThreads()))
                    .register(StormPrometheus.registry());

    private static final Counter REQUESTED =
            Counter.builder()
                    .name("storm_chunk_stream_requested_total")
                    .help(
                            "Chunks clients have asked for, counted as RequestZipListPacket.parse"
                                    + " appends them to ccrWaiting. This is the demand side. Compare"
                                    + " against storm_chunk_stream_sent_total (supply): while a player"
                                    + " drives, demand outruns supply and the difference accumulates as"
                                    + " storm_chunk_stream_backlog_chunks. Note the client re-requests"
                                    + " anything unanswered after 8s (WorldStreamer"
                                    + " .resendTimedOutRequests) with no backoff and no give-up, so once"
                                    + " the server falls 8s behind this rate roughly doubles on its own.")
                    .register(StormPrometheus.registry());

    private static final Histogram REQUEST_PACKET_CHUNKS =
            Histogram.builder()
                    .name("storm_chunk_stream_request_packet_chunks")
                    .help(
                            "Chunks carried by a single RequestZipList packet. WorldStreamer packs"
                                    + " every chunk the client currently wants into one packet with no"
                                    + " cap during normal play, so the tail of this is the burst a"
                                    + " grid-scroll or teleport dumps on the server at once — a 13x13"
                                    + " grid re-fill is 169 chunks in one packet.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter REQUEST_RESIDENCY =
            Counter.builder()
                    .name("storm_chunk_stream_request_residency_total")
                    .help(
                            "Chunks the client asked for, split by what ServerMap had at the moment"
                                    + " the request arrived. resident = a loaded ServerCell held the"
                                    + " chunk and the server could have answered immediately."
                                    + " cell_loading = the ServerCell exists but has not finished"
                                    + " hydrating, so the request is early and will be answered once"
                                    + " hydration lands. cell_absent = there is no ServerCell at all —"
                                    + " nothing is loading it, so unless a save file exists on disk the"
                                    + " request parks in queueUntilGenerated for a chunk the server"
                                    + " never even started. chunk_absent = the cell is loaded"
                                    + " but that slot is empty or the chunk in it is not itself loaded,"
                                    + " which should be rare and points at the cell, not the stream."
                                    + " Separating these matters because they"
                                    + " have opposite fixes: cell_loading wants faster hydration,"
                                    + " cell_absent wants the cell requested sooner (warmer reach,"
                                    + " lookahead), and only cell_absent explains a player who is stuck"
                                    + " rather than merely waiting. This is the same test as"
                                    + " storm_chunk_stream_dispatched_total but taken at request time"
                                    + " instead of dispatch time; the gap between the two non-resident"
                                    + " rates is demand that hydration caught up with while the request"
                                    + " sat in the queue.")
                    .labelNames("state")
                    .register(StormPrometheus.registry());

    private static final int STATE_CELL_LOADING = 0;
    private static final int STATE_CELL_ABSENT = 1;
    private static final int STATE_CHUNK_ABSENT = 2;

    private static final String[] ABSENT_STATES = {"cell_loading", "cell_absent", "chunk_absent"};

    private static final Histogram QUEUE_WAIT =
            Histogram.builder()
                    .name("storm_chunk_stream_queue_wait_seconds")
                    .help(
                            "How long a ClientChunkRequest sat between being allocated for a peer and"
                                    + " the download worker starting on it. The server dispatches one"
                                    + " request per connection per tick and only while that peer's"
                                    + " single worker is idle, so this is the delay the one-per-tick"
                                    + " rule imposes, isolated from how long the work itself takes"
                                    + " (storm_chunk_stream_batch_duration_seconds). kind is always"
                                    + " fresh since 42.20.3 removed the chunk retry ladder; the label"
                                    + " is kept for dashboard continuity. When a driver stalls, this"
                                    + " is where the seconds go.")
                    .labelNames("kind")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram BATCH_CHUNKS =
            Histogram.builder()
                    .name("storm_chunk_stream_batch_chunks")
                    .help(
                            "Chunks in one dispatched ClientChunkRequest, sampled as the worker"
                                    + " thread starts on it. Capped at 20 by"
                                    + " ClientChunkRequest.NON_LARGE_AREA_CHUNKS_LIMIT. Multiply the"
                                    + " mean by the dispatch rate to get the per-peer supply ceiling:"
                                    + " one batch per connection per server tick is the hard limit.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter DISPATCHED =
            Counter.builder()
                    .name("storm_chunk_stream_dispatched_total")
                    .help(
                            "Chunks handed to a download worker, split by whether the main thread"
                                    + " already had them. hot = PlayerDownloadServer.update found the"
                                    + " chunk in ServerMap and serialized it inline, so the worker only"
                                    + " has to compress and send. cold = ServerMap had no loaded chunk"
                                    + " there, so the worker must stat the save file and either block on"
                                    + " IsoChunk.SafeRead or park the request in queueUntilGenerated"
                                    + " until the chunk exists. A cold ratio that climbs while a player"
                                    + " drives is the server losing the race to hydrate cells ahead of"
                                    + " them.")
                    .labelNames("source")
                    .register(StormPrometheus.registry());

    private static final Histogram BATCH_DURATION =
            Histogram.builder()
                    .name("storm_chunk_stream_batch_duration_seconds")
                    .help(
                            "Wall time one download worker spends in sendArray. The worker's ready"
                                    + " flag is false for this entire span and"
                                    + " PlayerDownloadServer.update refuses to dispatch anything for"
                                    + " that peer while it is, so any batch longer than one server tick"
                                    + " directly costs dispatch slots. Compare against the tick period:"
                                    + " if p50 exceeds it, the worker is the limiter, not the"
                                    + " one-batch-per-tick rule.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter SENT =
            Counter.builder()
                    .name("storm_chunk_stream_sent_total")
                    .help(
                            "Chunks compressed and written to the wire as SentChunk packets. The"
                                    + " supply side, counted at the last point before RakNet. Note this"
                                    + " counts handoff to RakNet, not client receipt — sendChunk never"
                                    + " waits for an ACK, so on a slow link this leads actual delivery"
                                    + " by whatever the peer's send buffer holds"
                                    + " (storm_peer_send_buffer_bytes).")
                    .register(StormPrometheus.registry());

    private static final Counter SENT_BYTES =
            Counter.builder()
                    .name("storm_chunk_stream_sent_bytes_total")
                    .help(
                            "Compressed chunk payload bytes handed to RakNet. Excludes packet"
                                    + " framing. Storm has no other byte-level throughput counter, so"
                                    + " this is the only direct read on how much of a peer's bandwidth"
                                    + " the world download is consuming.")
                    .register(StormPrometheus.registry());

    private static final Counter SENT_RAW_BYTES =
            Counter.builder()
                    .name("storm_chunk_stream_sent_uncompressed_bytes_total")
                    .help(
                            "Serialized chunk bytes before Deflate. Divided into"
                                    + " storm_chunk_stream_sent_bytes_total this gives the live"
                                    + " compression ratio, which is what a change to the hardcoded"
                                    + " level-3 Deflater would move.")
                    .register(StormPrometheus.registry());

    private static final Histogram SENT_CHUNK_BYTES =
            Histogram.builder()
                    .name("storm_chunk_stream_sent_chunk_bytes")
                    .help(
                            "Compressed size of one chunk payload. SentChunkPacket fragments"
                                    + " anything over the MTU into multiple sends, so the tail here is"
                                    + " what turns a single chunk into a burst of packets on ordering"
                                    + " channel 0 — the same channel as gameplay traffic.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram COMPRESS_DURATION =
            Histogram.builder()
                    .name("storm_chunk_stream_compress_duration_seconds")
                    .help(
                            "Time in WorkerThread.compressChunk. Off the main thread, but it is a"
                                    + " serial component of storm_chunk_stream_batch_duration_seconds"
                                    + " and therefore of how long the peer's dispatch slot stays"
                                    + " blocked. Deflater level is hardcoded to 3.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram SERIALIZE_DURATION =
            Histogram.builder()
                    .name("pz_chunk_save_loaded_call_duration_seconds")
                    .help(
                            "Time in IsoChunk.SaveLoadedChunk — serializing one loaded chunk into a"
                                    + " request buffer. Two unrelated callers share this method, so"
                                    + " always filter on caller. caller=\"download\" is"
                                    + " PlayerDownloadServer.update on the server main thread, up to"
                                    + " 20 times per connection per tick — that is the chunk-streaming"
                                    + " path, and at N players streaming it multiplies straight into"
                                    + " tick time. caller=\"save\" is ServerCell.Save going to disk;"
                                    + " it fires 64 chunks per loaded cell on every SaveAll and would"
                                    + " otherwise swamp the download series by orders of magnitude.")
                    .labelNames("caller")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter SERIALIZED =
            Counter.builder()
                    .name("storm_chunk_stream_serialized_total")
                    .help(
                            "Chunks serialized by IsoChunk.SaveLoadedChunk, split by caller the same"
                                    + " way as pz_chunk_save_loaded_call_duration_seconds."
                                    + " caller=\"download\" equals the hot series of"
                                    + " storm_chunk_stream_dispatched_total minus chunks cancelled"
                                    + " between serialize and dispatch; caller=\"save\" is"
                                    + " persistence and has nothing to do with chunk streaming.")
                    .labelNames("caller")
                    .register(StormPrometheus.registry());

    private static final Counter NOT_REQUIRED =
            Counter.builder()
                    .name("storm_chunk_stream_not_required_total")
                    .help(
                            "NotRequiredInZip replies, by the sameOnServer flag the client"
                                    + " receives. true = the client's CRC matched, it already has a good"
                                    + " copy and no payload was needed — this is the bandwidth"
                                    + " optimisation working. false = the server is telling the client"
                                    + " to stop waiting without sending anything, which happens on a"
                                    + " serialize exception, a duplicate request, or a send error."
                                    + " A rising false rate means clients are being left with holes.")
                    .labelNames("same_on_server")
                    .register(StormPrometheus.registry());

    private static final Counter DUPLICATE_REQUESTS =
            Counter.builder()
                    .name("storm_chunk_stream_duplicate_requests_total")
                    .help(
                            "Queued chunk requests dropped by removeOlderDuplicateRequests because a"
                                    + " newer request for the same wx,wy was already waiting. Every one"
                                    + " of these is the client asking a second time for a chunk the"
                                    + " server had not yet answered — almost always its flat 8-second"
                                    + " resend timer firing while the request sat in ccrWaiting. This"
                                    + " is the best server-side proxy for client-perceived stall, and"
                                    + " it needs no client instrumentation to read. Each cancellation"
                                    + " also increments"
                                    + " storm_chunk_stream_not_required_total{same_on_server=\"false\"},"
                                    + " so subtracting this from that counter leaves the"
                                    + " serialization failures.")
                    .register(StormPrometheus.registry());

    private static final Histogram DEDUPE_DURATION =
            Histogram.builder()
                    .name("pz_player_download_dedupe_call_duration_seconds")
                    .help(
                            "Main-thread time in PlayerDownloadServer.removeOlderDuplicateRequests,"
                                    + " per connection per tick. The scan is waiting-requests x 20"
                                    + " chunks x waiting-requests x 20 chunks, so its cost rises with"
                                    + " the square of the backlog it exists to drain: a deep queue"
                                    + " steals main-thread time, which slows dispatch, which deepens"
                                    + " the queue. Watch this against"
                                    + " storm_chunk_stream_backlog_requests to see whether the"
                                    + " feedback loop has engaged.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Set<String> lastSeenUsernames = new HashSet<>();

    /**
     * Reused across ticks so the per-peer sweep does not allocate a fresh set 30 times a second.
     */
    private static final Set<String> currentUsernames = new HashSet<>();

    /** Scratch for {@link #countWaiting}: {@code {chunks, nonEmptyRequests}}. Main thread only. */
    private static final int[] waitingScratch = new int[2];

    /** When each departed peer's series was zeroed, for {@link #reapDepartedSeries(long)}. */
    private static final Map<String, Long> departedAtNanos = new HashMap<>();

    /**
     * How long a departed peer's zeroed series is kept before removal. Comfortably longer than any
     * sane scrape interval, so the zero is always scraped at least once.
     */
    private static final long DEPARTED_RETENTION_NANOS = 300L * 1_000_000_000L;

    /**
     * When each peer's current brake episode began, in {@code System.nanoTime()}. Absent means the
     * peer is not braked. Main thread only: written and read solely from {@link
     * #sampleTickInner()}.
     */
    private static final Map<String, Long> brakeSince = new HashMap<>();

    /**
     * Cached {@code PlayerDownloadServer$WorkerThread.ready}. Package-private in PZ, so it needs
     * reflection; resolved once on first sample and latched off on failure.
     */
    private static volatile Field readyField;

    /**
     * Cached {@code ClientChunkRequest.storm$enqueueNanos}, the field {@code
     * ClientChunkRequestRetryPatch} adds to hold the queue-wait start stamp. Injected bytecode is
     * invisible to javac, so it can only be reached reflectively; when the patch did not apply the
     * lookup latches off and {@link #QUEUE_WAIT} simply stays empty.
     */
    private static volatile Field enqueueField;

    private static volatile boolean readyFieldResolved;

    private static volatile boolean enqueueFieldResolved;

    /**
     * Set when a sample throws, so a PZ change degrades to one warning instead of a log flood.
     * Volatile because it is written from both the main thread and a download worker.
     */
    private static volatile boolean failed;

    private static final ThreadLocal<int[]> PERSISTENCE_DEPTH =
            ThreadLocal.withInitial(() -> new int[1]);

    private ChunkStreamMetrics() {}

    /**
     * Walk every connection and publish its chunk-download backlog and worker state.
     *
     * <p>Called from the server tick on the main thread. Peers that were present last tick and are
     * gone now have their label series zeroed, matching {@link StormConnectionMetrics}, so a
     * disconnect reads as a drop to zero rather than a series that freezes at its last value.
     */
    public static void sampleTick() {
        if (failed) {
            return;
        }
        try {
            sampleTickInner();
        } catch (Throwable t) {
            disable(t);
        }
    }

    private static void sampleTickInner() {
        UdpEngine engine = GameServer.udpEngine;
        if (engine == null) {
            return;
        }

        List<UdpConnection> connections = engine.connections;
        currentUsernames.clear();
        double maxBacklogChunks = 0.0;
        double maxCellHoles = 0.0;
        double maxBrakeCells = 0.0;
        double maxBrakeSeconds = 0.0;
        float minRunwayTiles = RUNWAY_HORIZON_TILES;
        long now = System.nanoTime();
        int busySamples = 0;
        int backloggedSamples = 0;
        int idleSamples = 0;

        for (int i = 0; i < connections.size(); i++) {
            UdpConnection c = connections.get(i);
            if (c == null) {
                continue;
            }
            PlayerDownloadServer pds = c.getPlayerDownloadServer();
            if (pds == null) {
                continue;
            }

            String username = labelFor(c);
            if (username == null) {
                continue;
            }
            currentUsernames.add(username);

            countWaiting(pds, waitingScratch);
            int waitingChunks = waitingScratch[0];
            int waitingRequests = waitingScratch[1];
            Boolean busy = isWorkerBusy(pds);

            BACKLOG_REQUESTS.labelValues(username).set(waitingRequests);
            BACKLOG_CHUNKS.labelValues(username).set(waitingChunks);
            if (busy != null) {
                WORKER_BUSY.labelValues(username).set(busy ? 1 : 0);
                if (busy) {
                    busySamples++;
                } else if (waitingRequests > 0) {
                    backloggedSamples++;
                } else {
                    idleSamples++;
                }
            }

            if (waitingChunks > maxBacklogChunks) {
                maxBacklogChunks = waitingChunks;
            }

            int cellHoles = countCellHoles(c);
            CELL_HOLES.labelValues(username).set(cellHoles);
            if (cellHoles > maxCellHoles) {
                maxCellHoles = cellHoles;
            }

            int brakeCells = countBrakeCells(c);
            BRAKE_CELLS.labelValues(username).set(brakeCells);
            if (brakeCells > maxBrakeCells) {
                maxBrakeCells = brakeCells;
            }

            double brakeSeconds = 0.0;
            if (brakeCells > 0) {
                Long since = brakeSince.get(username);
                if (since == null) {
                    brakeSince.put(username, now);
                } else {
                    brakeSeconds = (now - since) / 1_000_000_000.0;
                }
            } else {
                brakeSince.remove(username);
            }
            BRAKE_SECONDS.labelValues(username).set(brakeSeconds);
            if (brakeSeconds > maxBrakeSeconds) {
                maxBrakeSeconds = brakeSeconds;
            }

            float speed = 0.0f;
            float runway = RUNWAY_HORIZON_TILES;
            for (int index = 0; index < 4; index++) {
                IsoPlayer player = c.players[index];
                if (player == null) {
                    continue;
                }
                BaseVehicle vehicle = player.getVehicle();
                if (vehicle == null) {
                    continue;
                }
                // .z is world Y. getCurrentSpeedKmHour would be the obvious call and must not be
                // used: it assigns jniSpeed = 0 whenever the driver is not a local player, so a
                // sampler calling it would blank the field the rest of the server reads.
                float vx = vehicle.jniLinearVelocity.x;
                float vy = vehicle.jniLinearVelocity.z;
                float indexSpeed = (float) Math.sqrt(vx * vx + vy * vy);
                if (indexSpeed > speed) {
                    speed = indexSpeed;
                }
                if (indexSpeed < RUNWAY_MIN_SPEED) {
                    continue;
                }
                float ahead =
                        runwayTiles(
                                vehicle.getX(), vehicle.getY(), vx / indexSpeed, vy / indexSpeed);
                if (ahead < runway) {
                    runway = ahead;
                }
            }
            SPEED_TILES_PER_SECOND.labelValues(username).set(speed);
            RUNWAY_TILES.labelValues(username).set(runway);
            if (runway < minRunwayTiles) {
                minRunwayTiles = runway;
            }
        }

        BACKLOG_CHUNKS_MAX.set(maxBacklogChunks);
        CELL_HOLES_MAX.set(maxCellHoles);
        BRAKE_CELLS_MAX.set(maxBrakeCells);
        BRAKE_SECONDS_MAX.set(maxBrakeSeconds);
        RUNWAY_TILES_MIN.set(minRunwayTiles);

        if (busySamples > 0) {
            WORKER_SAMPLES.labelValues("busy").inc(busySamples);
        }
        if (backloggedSamples > 0) {
            WORKER_SAMPLES.labelValues("ready_backlogged").inc(backloggedSamples);
        }
        if (idleSamples > 0) {
            WORKER_SAMPLES.labelValues("ready_idle").inc(idleSamples);
        }

        for (String prev : lastSeenUsernames) {
            if (currentUsernames.contains(prev)) {
                continue;
            }
            BACKLOG_REQUESTS.labelValues(prev).set(0.0);
            BACKLOG_CHUNKS.labelValues(prev).set(0.0);
            WORKER_BUSY.labelValues(prev).set(0.0);
            CELL_HOLES.labelValues(prev).set(0.0);
            BRAKE_CELLS.labelValues(prev).set(0.0);
            BRAKE_SECONDS.labelValues(prev).set(0.0);
            SPEED_TILES_PER_SECOND.labelValues(prev).set(0.0);
            RUNWAY_TILES.labelValues(prev).set(RUNWAY_HORIZON_TILES);
            brakeSince.remove(prev);
            departedAtNanos.put(prev, now);
        }
        lastSeenUsernames.clear();
        lastSeenUsernames.addAll(currentUsernames);
        reapDepartedSeries(now);
    }

    /**
     * Drop the label series of players who left, once a scrape has certainly seen the zero the
     * disconnect sweep wrote.
     *
     * <p>Zeroing alone is not enough: a Prometheus child stays in the registry for the life of the
     * process, so six series per player would accumulate with <em>lifetime</em> unique logins
     * rather than concurrent ones. Removing immediately is not right either — the series would
     * vanish still holding its last non-zero value, and a backlog that ended in a disconnect would
     * read as a backlog that never ended. So: zero now, remove {@link #DEPARTED_RETENTION_NANOS}
     * later.
     */
    private static void reapDepartedSeries(long now) {
        if (departedAtNanos.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, Long>> it = departedAtNanos.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            String username = entry.getKey();
            if (currentUsernames.contains(username)) {
                it.remove();
                continue;
            }
            if (now - entry.getValue() < DEPARTED_RETENTION_NANOS) {
                continue;
            }
            BACKLOG_REQUESTS.remove(username);
            BACKLOG_CHUNKS.remove(username);
            WORKER_BUSY.remove(username);
            CELL_HOLES.remove(username);
            BRAKE_CELLS.remove(username);
            BRAKE_SECONDS.remove(username);
            SPEED_TILES_PER_SECOND.remove(username);
            RUNWAY_TILES.remove(username);
            it.remove();
        }
    }

    /**
     * Counts cells still flagged unloaded in the server's copy of a peer's cell mirror, across all
     * four split-screen player indices. The mirror is a {@code boolean[]} of {@code width * width}
     * over the cells around that player, so this is a handful of array reads per index.
     */
    private static int countCellHoles(UdpConnection c) {
        int holes = 0;
        for (int index = 0; index < 4; index++) {
            ClientServerMap mirror = mirrorOf(c, index);
            if (mirror == null) {
                continue;
            }
            for (boolean loaded : mirror.loaded) {
                if (!loaded) {
                    holes++;
                }
            }
        }
        return holes;
    }

    /**
     * Counts unloaded mirror cells close enough to the peer to brake a vehicle. {@code centerX/Y}
     * is the square the mirror was last centred on, so the peer's own cell is that square divided
     * down to cell coordinates; the 3x3 window around it covers every cell the 16-tile lookahead in
     * {@code BaseVehicle.isInvalidChunkAhead} can reach.
     */
    private static int countBrakeCells(UdpConnection c) {
        int holes = 0;
        for (int index = 0; index < 4; index++) {
            ClientServerMap mirror = mirrorOf(c, index);
            if (mirror == null) {
                continue;
            }
            int cellX = PZMath.coorddivision(PZMath.coorddivision(mirror.centerX, 8), 8);
            int cellY = PZMath.coorddivision(PZMath.coorddivision(mirror.centerY, 8), 8);
            int originX = cellX - mirror.getMinX();
            int originY = cellY - mirror.getMinY();

            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = originX + dx;
                    int y = originY + dy;
                    if (mirror.isValidCell(x, y) && !mirror.loaded[x + y * mirror.width]) {
                        holes++;
                    }
                }
            }
        }
        return holes;
    }

    /**
     * Distance in tiles from {@code (x, y)} to the first cell {@code ServerMap} has not hydrated,
     * marching along the unit vector {@code (dirX, dirY)} and stopping at {@link
     * #RUNWAY_HORIZON_TILES}.
     *
     * <p>Deliberately reads {@code ServerMap} rather than the peer's {@code ClientServerMap}
     * mirror. The mirror says what the client has been sent, which {@link #countBrakeCells} already
     * covers; this says whether the world in front of the player exists on the server at all. Those
     * are the two different failures behind the same stall, and only the second one is the cell
     * loader's fault.
     *
     * <p>Runs on the tick thread, so {@code isLoaded} is read on the thread that flips it.
     */
    private static float runwayTiles(float x, float y, float dirX, float dirY) {
        ServerMap map = ServerMap.instance;
        if (map == null) {
            return RUNWAY_HORIZON_TILES;
        }
        int lastCellX = Integer.MIN_VALUE;
        int lastCellY = Integer.MIN_VALUE;
        for (float travelled = 0.0f;
                travelled <= RUNWAY_HORIZON_TILES;
                travelled += RUNWAY_STEP_TILES) {
            int cellX = map.worldSquareToServerCellXY(PZMath.fastfloor(x + dirX * travelled));
            int cellY = map.worldSquareToServerCellXY(PZMath.fastfloor(y + dirY * travelled));
            if (cellX == lastCellX && cellY == lastCellY) {
                continue;
            }
            lastCellX = cellX;
            lastCellY = cellY;
            ServerMap.ServerCell cell = map.getCell(cellX - map.getMinX(), cellY - map.getMinY());
            if (cell == null || !cell.isLoaded) {
                return travelled;
            }
        }
        return RUNWAY_HORIZON_TILES;
    }

    /**
     * The peer's cell mirror for one player index, or null when there is nothing worth counting. A
     * mirror is allocated at connect but only recentred once {@code ClientServerMap.characterIn}
     * finds a live player, so an index with no player would otherwise report its all-false
     * constructor state as a full grid of holes and look identical to a driving stall.
     */
    private static ClientServerMap mirrorOf(UdpConnection c, int playerIndex) {
        ClientServerMap mirror = c.getLoadedCell(playerIndex);
        if (mirror == null || mirror.loaded == null || c.players[playerIndex] == null) {
            return null;
        }
        return mirror;
    }

    /**
     * Why {@code ServerMap} could not answer for a chunk, as an index into {@link #ABSENT_STATES}.
     *
     * <p>Only reached for chunks {@code getChunk} already returned nothing for, so the cheap test
     * stays on the common path. {@code getChunk} folds three different failures into one null — no
     * cell, cell still hydrating, and empty slot in a loaded cell — and they need opposite fixes,
     * so this repeats its coordinate maths to tell them apart. Coordinates are converted the same
     * way {@code getChunk} does: chunk to cell, then shifted into the {@code cellMap} frame.
     *
     * <p>{@code RequestZipListPacket} is {@code handlingType = 1}, so {@code GameServer} parses it
     * inline from {@code mainLoopDealWithNetData} — the same thread that flips {@code isLoaded}.
     * This classification is exact, not a racy sample.
     */
    private static int classifyAbsent(ServerMap map, int wx, int wy) {
        int cx = map.worldChunkToServerCellXY(wx);
        int cy = map.worldChunkToServerCellXY(wy);
        ServerMap.ServerCell cell = map.getCell(cx - map.getMinX(), cy - map.getMinY());
        if (cell == null) {
            return STATE_CELL_ABSENT;
        }
        return cell.isLoaded ? STATE_CHUNK_ABSENT : STATE_CELL_LOADING;
    }

    /**
     * Record the demand a single {@code RequestZipList} packet added, called from the exit advice
     * on {@code RequestZipListPacket.parse} with the queue depth captured on entry.
     *
     * <p>{@code parse} appends to the tail of {@code ccrWaiting}, so summing the requests past
     * {@code waitingBefore} counts exactly what this packet asked for. The download worker also
     * appends retries to that tail from its own thread; when one lands mid-parse its chunks are
     * attributed here as fresh demand. That is rare, bounded by 20 chunks, and the alternative —
     * locking a list the tick path walks — is worse.
     */
    public static void recordRequestPacket(PlayerDownloadServer pds, int waitingBefore) {
        if (failed || pds == null || waitingBefore < 0) {
            return;
        }
        try {
            List<ClientChunkRequest> waiting = pds.ccrWaiting;
            ServerMap map = ServerMap.instance;
            int chunks = 0;
            int resident = 0;
            int[] residency = new int[ABSENT_STATES.length];
            for (int i = waitingBefore; i < waiting.size(); i++) {
                ClientChunkRequest request;
                try {
                    request = waiting.get(i);
                } catch (IndexOutOfBoundsException e) {
                    break;
                }
                if (request == null) {
                    continue;
                }
                List<ClientChunkRequest.Chunk> requested = request.chunks;
                chunks += requested.size();
                if (map == null) {
                    continue;
                }
                for (int j = 0; j < requested.size(); j++) {
                    ClientChunkRequest.Chunk chunk;
                    try {
                        chunk = requested.get(j);
                    } catch (IndexOutOfBoundsException e) {
                        break;
                    }
                    if (chunk == null) {
                        continue;
                    }
                    IsoChunk hydrated = map.getChunk(chunk.wx, chunk.wy);
                    if (hydrated != null && hydrated.loaded) {
                        resident++;
                    } else {
                        residency[classifyAbsent(map, chunk.wx, chunk.wy)]++;
                    }
                }
            }
            REQUESTED.inc(chunks);
            REQUEST_PACKET_CHUNKS.observe(chunks);
            if (resident > 0) {
                REQUEST_RESIDENCY.labelValues("resident").inc(resident);
            }
            for (int i = 0; i < ABSENT_STATES.length; i++) {
                if (residency[i] > 0) {
                    REQUEST_RESIDENCY.labelValues(ABSENT_STATES[i]).inc(residency[i]);
                }
            }
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("Storm: chunk-stream request accounting failed and is now disabled", t);
        }
    }

    /**
     * Classify a batch as the worker picks it up, splitting chunks by whether {@code
     * PlayerDownloadServer.update} already serialized them on the main thread.
     *
     * <p>A non-null {@code bb} is the only observable trace of that decision: {@code update} calls
     * {@code getByteBuffer} then {@code SaveLoadedChunk} only when {@code ServerMap} returned a
     * loaded chunk, so {@code bb == null} here means the main thread found nothing and the worker
     * now owns the slow path.
     */
    public static void recordBatchStart(ClientChunkRequest ccr) {
        if (failed || ccr == null) {
            return;
        }
        try {
            List<ClientChunkRequest.Chunk> chunks = ccr.chunks;
            recordQueueWait(ccr, "fresh");
            int hot = 0;
            int cold = 0;
            for (int i = 0; i < chunks.size(); i++) {
                ClientChunkRequest.Chunk chunk;
                try {
                    chunk = chunks.get(i);
                } catch (IndexOutOfBoundsException e) {
                    break;
                }
                if (chunk == null) {
                    continue;
                }
                if (chunk.bb != null) {
                    hot++;
                } else {
                    cold++;
                }
            }
            BATCH_CHUNKS.observe(hot + cold);
            if (hot > 0) {
                DISPATCHED.labelValues("hot").inc(hot);
            }
            if (cold > 0) {
                DISPATCHED.labelValues("cold").inc(cold);
            }
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("Storm: chunk-stream batch accounting failed and is now disabled", t);
        }
    }

    /**
     * One warning then silence, shared by every recorder in this class. The download worker and the
     * main thread both call in, so the latch is volatile and re-entry is ignored.
     */
    private static void disable(Throwable cause) {
        if (failed) {
            return;
        }
        failed = true;
        LOGGER.warn(
                "Storm: chunk-stream metrics failed and are now disabled for this session", cause);
    }

    public static void recordBatchDuration(long nanos) {
        if (failed) {
            return;
        }
        try {
            BATCH_DURATION.observe(nanos / 1e9);
        } catch (Throwable t) {
            disable(t);
        }
    }

    public static void recordCompressed(int rawBytes, int compressedBytes, long nanos) {
        if (failed) {
            return;
        }
        try {
            SENT.inc();
            if (rawBytes > 0) {
                SENT_RAW_BYTES.inc(rawBytes);
            }
            if (compressedBytes > 0) {
                SENT_BYTES.inc(compressedBytes);
                SENT_CHUNK_BYTES.observe(compressedBytes);
            }
            COMPRESS_DURATION.observe(nanos / 1e9);
        } catch (Throwable t) {
            disable(t);
        }
    }

    /**
     * Bracket the persistence path so {@code recordSerialize} can tell the two callers of {@code
     * IsoChunk.SaveLoadedChunk} apart. {@code ServerChunkLoader.addSaveLoadedJob} delegates
     * straight to {@code SaveChunkThread.addLoadedJob}, which serializes synchronously on the
     * calling thread before handing the result to the save queue — so the marker is a thread-local
     * depth, not a static flag. The caller can be the main thread ({@code SaveAll}) or a {@code
     * ServerMap} worker, which is why thread identity alone cannot classify it.
     */
    public static void enterPersistenceSave() {
        PERSISTENCE_DEPTH.get()[0]++;
    }

    public static void exitPersistenceSave() {
        int[] depth = PERSISTENCE_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    public static void recordSerialize(long nanos) {
        if (failed) {
            return;
        }
        try {
            boolean persistence = PERSISTENCE_DEPTH.get()[0] > 0;
            String caller = persistence ? "save" : "download";
            SERIALIZED.labelValues(caller).inc();
            SERIALIZE_DURATION.labelValues(caller).observe(nanos / 1e9);
            MainLoopStepTimings.record(
                    persistence ? "IsoChunk.SaveLoadedChunk(save)" : "IsoChunk.SaveLoadedChunk",
                    nanos);
        } catch (Throwable t) {
            disable(t);
        }
    }

    public static void recordNotRequired(boolean sameOnServer) {
        if (failed) {
            return;
        }
        try {
            NOT_REQUIRED.labelValues(sameOnServer ? "true" : "false").inc();
        } catch (Throwable t) {
            disable(t);
        }
    }

    public static void recordDuplicateCancelled() {
        if (failed) {
            return;
        }
        try {
            DUPLICATE_REQUESTS.inc();
        } catch (Throwable t) {
            disable(t);
        }
    }

    public static void recordDedupeDuration(long nanos) {
        if (failed) {
            return;
        }
        try {
            DEDUPE_DURATION.observe(nanos / 1e9);
        } catch (Throwable t) {
            disable(t);
        }
    }

    /**
     * Sum the chunks across every queued request, writing {@code {chunks, nonEmptyRequests}} into
     * {@code out}.
     *
     * <p>Empty requests are counted separately because cancellation and dedupe drain a queued
     * request's chunks in place, leaving the empty shell in {@code ccrWaiting} until the queue
     * reaps it. Counting it as backlog would tip {@code workerState} from {@code ready_idle} to
     * {@code ready_backlogged} on ticks where no real demand is waiting.
     *
     * <p>Index-bounded rather than a for-each: {@code sendArray} appends to {@code ccrWaiting} from
     * the worker thread while the main thread removes from the front, and an iterator over that
     * would throw {@code ConcurrentModificationException} on the tick path. Re-reading {@code
     * size()} each step and null-checking the element makes a concurrent structural change cost one
     * slightly-off sample instead.
     */
    private static void countWaiting(PlayerDownloadServer pds, int[] out) {
        List<ClientChunkRequest> waiting = pds.ccrWaiting;
        int total = 0;
        int nonEmpty = 0;
        for (int i = 0; i < waiting.size(); i++) {
            ClientChunkRequest request;
            try {
                request = waiting.get(i);
            } catch (IndexOutOfBoundsException e) {
                break;
            }
            if (request != null) {
                int chunks = request.chunks.size();
                total += chunks;
                if (chunks > 0) {
                    nonEmpty++;
                }
            }
        }
        out[0] = total;
        out[1] = nonEmpty;
    }

    /**
     * Whether the peer's download worker is mid-batch, or {@code null} when the {@code ready} flag
     * cannot be read.
     *
     * <p>Nullable rather than defaulting to {@code false}: the busy/ready split is the whole point
     * of {@code storm_chunk_stream_worker_samples_total}, and a hard {@code false} would classify
     * every sample as {@code ready_backlogged} or {@code ready_idle} — a confident, plausible,
     * wrong answer pointing at the dispatch rule. Callers skip the sample instead, so the series
     * goes flat and the failure reads as missing data.
     */
    private static Boolean isWorkerBusy(PlayerDownloadServer pds) {
        PlayerDownloadServer.WorkerThread worker = pds.workerThread;
        if (worker == null) {
            return null;
        }
        Field field = readyField();
        if (field == null) {
            return null;
        }
        try {
            return !field.getBoolean(worker);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            readyField = null;
            readyFieldResolved = true;
            LOGGER.warn("Storm: cannot read PlayerDownloadServer worker ready flag", e);
            return null;
        }
    }

    /**
     * Start the queue-wait clock, called from the exit advice on {@code
     * PlayerDownloadServer.getClientChunkRequest} — the one funnel all four {@code ccrWaiting}
     * enqueue sites allocate through, on both the packet-parse and download-worker threads.
     *
     * <p>Stamping on acquisition rather than on insertion is what makes the pooling in {@code
     * freeRequests} harmless: a recycled request is re-stamped before its next use, so a stale
     * timestamp can never survive into a later batch.
     */
    public static void stampEnqueue(Object ccr) {
        if (failed || ccr == null) {
            return;
        }
        Field field = enqueueField();
        if (field == null) {
            return;
        }
        try {
            field.setLong(ccr, System.nanoTime());
        } catch (IllegalAccessException | IllegalArgumentException e) {
            enqueueField = null;
            enqueueFieldResolved = true;
            LOGGER.warn("Storm: cannot stamp chunk-request enqueue time", e);
        }
    }

    private static void recordQueueWait(ClientChunkRequest ccr, String kind) {
        Field field = enqueueField();
        if (field == null) {
            return;
        }
        long enqueued;
        try {
            enqueued = field.getLong(ccr);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            enqueueField = null;
            enqueueFieldResolved = true;
            LOGGER.warn("Storm: cannot read chunk-request enqueue time", e);
            return;
        }
        if (enqueued == 0L) {
            return;
        }
        QUEUE_WAIT.labelValues(kind).observe(Math.max(0L, System.nanoTime() - enqueued) / 1e9);
    }

    /**
     * Resolve the injected stamp field. Unlike {@link #readyField()} this is reachable from the
     * packet-parse thread and the download worker at once, so the resolved handle is published
     * before the latch is raised — a racing thread must never see {@code resolved} without also
     * seeing the field it guards.
     */
    private static Field enqueueField() {
        if (enqueueFieldResolved) {
            return enqueueField;
        }
        try {
            Field field = ClientChunkRequest.class.getDeclaredField("storm$enqueueNanos");
            field.setAccessible(true);
            enqueueField = field;
        } catch (NoSuchFieldException | RuntimeException e) {
            LOGGER.warn(
                    "Storm: ClientChunkRequest.storm$enqueueNanos not found —"
                            + " storm_chunk_stream_queue_wait_seconds will stay empty",
                    e);
        }
        enqueueFieldResolved = true;
        return enqueueField;
    }

    private static Field readyField() {
        if (readyFieldResolved) {
            return readyField;
        }
        try {
            Field field = PlayerDownloadServer.WorkerThread.class.getDeclaredField("ready");
            field.setAccessible(true);
            readyField = field;
        } catch (NoSuchFieldException | RuntimeException e) {
            LOGGER.warn(
                    "Storm: PlayerDownloadServer$WorkerThread.ready not found —"
                            + " storm_chunk_stream_worker_busy will stay empty",
                    e);
        }
        readyFieldResolved = true;
        return readyField;
    }

    private static int countWorkerThreads() {
        int count = 0;
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        while (group.getParent() != null) {
            group = group.getParent();
        }
        Thread[] threads = new Thread[group.activeCount() + 64];
        int found = group.enumerate(threads, true);
        while (found == threads.length) {
            threads = new Thread[threads.length * 2];
            found = group.enumerate(threads, true);
        }
        for (int i = 0; i < found; i++) {
            Thread t = threads[i];
            if (t != null && t.getName().startsWith("PlayerDownloadServer")) {
                count++;
            }
        }
        return count;
    }

    /**
     * The peer's username, or {@code null} before login sets one.
     *
     * <p>There is deliberately no GUID fallback. {@code getConnectedGUID} is unique per RakNet
     * connection, so falling back to it would mint six permanent gauge series on every connection
     * attempt — reconnect churn or a port scanner would then grow the registry without bound. Peers
     * are simply not sampled until they are named; they are only unnamed during the handshake,
     * before any chunk streaming happens.
     */
    private static String labelFor(UdpConnection c) {
        String name = c.getUserName();
        return name == null || name.isEmpty() ? null : name;
    }
}
