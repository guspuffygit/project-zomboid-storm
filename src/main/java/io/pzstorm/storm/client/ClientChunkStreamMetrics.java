package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.datapoints.DistributionDataPoint;
import io.prometheus.metrics.core.datapoints.GaugeDataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.LoadChunkEvent;
import io.pzstorm.storm.event.lua.OnTickEvenPausedEvent;
import io.pzstorm.storm.metrics.StormPrometheus;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import zombie.characters.IsoPlayer;
import zombie.core.physics.CarController;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoWorld;
import zombie.iso.WorldStreamer;
import zombie.network.ClientServerMap;
import zombie.network.GameClient;
import zombie.network.PassengerMap;
import zombie.vehicles.BaseVehicle;

/**
 * Client half of the chunk-streaming picture: pipeline depth, arrival rate, and the four ways a
 * missing chunk stops the player moving.
 *
 * <p>The server-side metrics ({@code storm_chunk_stream_*}) end at the wire. Everything after that
 * — decompression, the main-thread hydration budget, the 8-second resend timer, and the force-brake
 * — is invisible from the server, and players' clients are not scrapeable. This class closes the
 * loop for a client launched with {@code -DprometheusPort}, which is the only condition under which
 * it is registered at all: {@code StatisticManager.init()} starts its Prometheus {@code HTTPServer}
 * on that property alone, with no {@code GameServer.server} check, bound to {@code
 * PrometheusRegistry.defaultRegistry} — the same registry {@link StormPrometheus#registry()}
 * returns. So Storm's series appear on the client's {@code /metrics} with no new transport.
 *
 * <p>Sampling runs on {@link OnTickEvenPausedEvent}, which fires once per frame from {@code
 * IngameState.updateInternal} — after {@code WorldStreamer.updateMain()} has built and sent this
 * frame's request batch and before {@code IsoChunkMap.update()} drains the hydration queue, so both
 * sides of the handoff are observed at their peak. {@code OnRenderTickEvent} would be the obvious
 * alternative and is wrong: it is gated on {@code GameWindow.doRenderEvent}, which Lua sets false
 * on entering a world.
 *
 * <p>Arrivals come from {@link LoadChunkEvent}, which vanilla already fires once per chunk inserted
 * into the world. That event is the only exact delivery count obtainable without a patch — the
 * {@code ChunkRequest} that carried the chunk is pooled and released on the same World Streamer
 * pass that completes it, so a per-frame sampler can only ever see requests still in flight.
 *
 * <p>Everything here reads: sizes of the streamer's queues, a handful of public statics, and two
 * private booleans. It never mutates game state and never takes a game lock. The streamer's backlog
 * lists are plain {@code ArrayList}s mutated by the World Streamer and UdpEngine threads with no
 * lock, so they are treated as racy: {@code size()} is a plain int read that at worst returns a
 * stale count, and the one place that does walk a list — {@link #scanInFlight} — indexes it
 * defensively and abandons the walk on the first sign of concurrent mutation. Every sample is
 * fenced; the first failure logs once and disables sampling for the rest of the session rather than
 * throwing on the frame path.
 */
public final class ClientChunkStreamMetrics {

    /** Frames between the two O(gridWidth^2) scans; ~10 Hz at 60 fps, matching the server tick. */
    private static final int SCAN_STRIDE = 6;

    /**
     * Radius in chunks of the state scan, matching the widest {@code isInvalidChunkAround} reach.
     */
    private static final int AHEAD_RADIUS = 2;

    private static final Gauge QUEUE_DEPTH =
            Gauge.builder()
                    .name("storm_client_chunk_queue_depth")
                    .help(
                            "Chunks or requests sitting at each stage of the client streaming"
                                    + " pipeline, in pipeline order. main_to_ws ="
                                    + " WorldStreamer.chunkRequests0, the main thread's handoff of a"
                                    + " newly wanted chunk to the streamer thread; wanted ="
                                    + " chunkRequests1, sorted by distance and waiting to be turned"
                                    + " into a request (the streamer thread polls at 20ms while it has"
                                    + " work and 280ms while idle, so a newly wanted chunk can wait a"
                                    + " quarter second before it even becomes a request); ws_to_main ="
                                    + " mainThreadRequestQueue, requests built by the streamer thread"
                                    + " waiting for the main thread to put them on the wire; in_flight"
                                    + " = pendingRequests1, built and unanswered, still owned by the"
                                    + " streamer thread; sent_handoff = sentRequests, sent by"
                                    + " updateMain and waiting to be handed back to the streamer"
                                    + " thread; in_flight_net = pendingRequests, confirmed on the wire"
                                    + " and awaiting server data — this is the one that measures the"
                                    + " server; cancel_pending = waitingToCancelQ, cancellations the"
                                    + " main thread has not flushed yet; hydration ="
                                    + " IsoChunk.loadGridSquare, fully decompressed and waiting for the"
                                    + " main thread to insert it. Backlog piling up in wanted means the"
                                    + " streamer thread is the limiter, in in_flight_net means the"
                                    + " server is, and in hydration means the main-thread drain budget"
                                    + " is.")
                    .labelNames("stage")
                    .register(StormPrometheus.registry());

    private static final Gauge HYDRATION_BUDGET =
            Gauge.builder()
                    .name("storm_client_chunk_hydration_budget")
                    .help(
                            "Chunks IsoChunkMap.updateInternal is allowed to insert into the world"
                                    + " this frame, recomputed as 1 + depth*3/chunkGridWidth. This is"
                                    + " the only per-frame cap in the whole receive path — nothing"
                                    + " throttles reception or decompression. The formula is"
                                    + " deliberately backlog-proportional, so compare it against"
                                    + " storm_client_chunk_queue_depth{stage=\"hydration\"}: a depth"
                                    + " that grows while the budget stays small means the drain is"
                                    + " losing ground.")
                    .register(StormPrometheus.registry());

    private static final Gauge GRID_HOLES =
            Gauge.builder()
                    .name("storm_client_chunk_grid_holes")
                    .help(
                            "Null cells in the local player's IsoChunkMap grid — chunks inside the"
                                    + " player's own view that are not in the world yet. This is what"
                                    + " the player actually sees missing, as opposed to what the"
                                    + " server thinks it has sent.")
                    .register(StormPrometheus.registry());

    private static final Gauge SERVER_CELL_HOLES =
            Gauge.builder()
                    .name("storm_client_server_cell_holes")
                    .help(
                            "Unloaded flags in the client's ClientServerMap mirror of the server's"
                                    + " cell grid. One flag covers a whole 8x8-chunk (64x64-tile)"
                                    + " ServerCell, and the vehicle brake reads this mirror rather"
                                    + " than the client's own chunk map — so this being non-zero can"
                                    + " brake a car whose surrounding terrain is fully loaded.")
                    .register(StormPrometheus.registry());

    private static final Gauge STREAMER_BUSY =
            Gauge.builder()
                    .name("storm_client_chunk_streamer_busy")
                    .help(
                            "1 when WorldStreamer.isBusy() reports outstanding work at sample time."
                                    + " Pair with storm_client_chunk_queue_depth: busy with an empty"
                                    + " hydration queue and a non-zero in_flight is the signature of"
                                    + " waiting on the server.")
                    .register(StormPrometheus.registry());

    private static final Gauge LARGE_AREA =
            Gauge.builder()
                    .name("storm_client_chunk_requesting_large_area")
                    .help(
                            "1 while WorldStreamer.requestingLargeArea is set (initial world load or"
                                    + " teleport). In this mode the streamer caps itself at 40"
                                    + " in-flight requests and stops cancelling out-of-range ones, so"
                                    + " backlog and cancel metrics read differently — use this to"
                                    + " exclude load-in from steady-state driving analysis.")
                    .register(StormPrometheus.registry());

    private static final Gauge GRID_WIDTH =
            Gauge.builder()
                    .name("storm_client_chunk_grid_width")
                    .help(
                            "IsoChunkMap.chunkGridWidth, the client's view size in chunks. Sets both"
                                    + " the hydration budget divisor and the vehicle brake lookahead:"
                                    + " >7 looks 2 chunks (16 tiles) ahead, >4 looks 1, and <=4"
                                    + " disables the lookahead entirely.")
                    .register(StormPrometheus.registry());

    private static final Gauge VEHICLE_SPEED =
            Gauge.builder()
                    .name("storm_client_vehicle_speed_kmh")
                    .help(
                            "Local player's current vehicle speed, 0 when on foot. The brake"
                                    + " lookahead is a fixed 16 tiles regardless of speed, so this is"
                                    + " the x-axis for every stall metric below: at 100 km/h those 16"
                                    + " tiles are well under a second of warning.")
                    .register(StormPrometheus.registry());

    private static final Counter REQUESTS =
            Counter.builder()
                    .name("storm_client_chunk_requests_total")
                    .help(
                            "Chunk requests the client has created, from the delta of"
                                    + " WorldStreamer.requestNumber (incremented exactly once per"
                                    + " ChunkRequest). Counts re-requests after the 8-second timeout"
                                    + " as new requests, so requests_total minus arrivals_total over a"
                                    + " window is wasted request volume. The counter restarts when the"
                                    + " streamer is replaced on reconnect; a decrease is treated as a"
                                    + " restart and contributes nothing.")
                    .register(StormPrometheus.registry());

    private static final Counter ARRIVALS =
            Counter.builder()
                    .name("storm_client_chunk_arrivals_total")
                    .help(
                            "Chunks inserted into the client's world, counted from the vanilla"
                                    + " LoadChunk event at the end of IsoChunk.doLoadGridsquare. This"
                                    + " is the client-side delivery rate to compare against the"
                                    + " server's storm_chunk_stream_sent_total: a persistent"
                                    + " gap is loss, timeout-discard, or backlog.")
                    .register(StormPrometheus.registry());

    private static final Counter CANCELS =
            Counter.builder()
                    .name("storm_client_chunk_cancels_total")
                    .help(
                            "Requests the client cancelled after issuing them, summed from the"
                                    + " per-frame WorldStreamer.tempRequests batch. A chunk is"
                                    + " cancelled once no chunk map still references it — usually"
                                    + " because the player moved past it before the server answered."
                                    + " High cancels while driving means the stream is delivering"
                                    + " terrain the player has already left behind.")
                    .register(StormPrometheus.registry());

    private static final Counter SUPPRESSED_PACKETS =
            Counter.builder()
                    .name("storm_client_packet_suppressed_total")
                    .help(
                            "Outgoing packets the client dropped for exceeding"
                                    + " MaxPacketsPerSecond, by packet type. PacketType.send calls"
                                    + " cancelPacket() and returns normally, so the caller has no way"
                                    + " to know: WorldStreamer.updateMain has already moved the"
                                    + " requests into sentRequests by then, and the client goes on"
                                    + " believing it asked for chunks it never sent. They only recover"
                                    + " when the flat 8-second resend timer fires, which is a whole"
                                    + " stall on its own. Nothing else can see this — PacketsCache"
                                    + " keeps a sliding one-second window of timestamps and no"
                                    + " cumulative count, and the only trace is a Multiplayer debug"
                                    + " warn. RequestZipList here is silent chunk-request loss;"
                                    + " anything else is a client that is talking too fast for the"
                                    + " server's limit.")
                    .labelNames("type")
                    .register(StormPrometheus.registry());

    private static final Counter AHEAD_SAMPLES =
            Counter.builder()
                    .name("storm_client_chunk_ahead_samples_total")
                    .help(
                            "Per-scan classification of every chunk within 2 chunks of the local"
                                    + " player, cross-referencing the coarse ClientServerMap cell"
                                    + " mirror against the client's own chunk map. loaded = both agree"
                                    + " it is there; client_chunk_missing = the server's cell is"
                                    + " flagged loaded but the client has no chunk (a genuine delivery"
                                    + " gap); server_cell_missing = the client has the chunk but the"
                                    + " cell mirror says otherwise, which brakes the car anyway and is"
                                    + " pure 64x64-tile granularity loss; both_missing = neither. The"
                                    + " server_cell_missing share is the direct measure of how much of"
                                    + " the driving stall is coarse bookkeeping rather than real"
                                    + " missing terrain.")
                    .labelNames("state")
                    .register(StormPrometheus.registry());

    private static final Counter STALL_EVENTS =
            Counter.builder()
                    .name("storm_client_chunk_stall_events_total")
                    .help(
                            "Times each movement-blocking mechanism engaged, counted on the rising"
                                    + " edge. invalid_chunk_ahead / invalid_chunk_behind ="
                                    + " BaseVehicle.isInvalidChunkAhead/Behind, the predicate"
                                    + " CarController brakes on; brake_forced = that predicate true"
                                    + " while the brake is applied, i.e. the force-brake actually"
                                    + " fired (zero engine force, transmission to neutral, throttle"
                                    + " decaying); physics_disabled ="
                                    + " disableSimulationDueToLackOfSurroundingChunks, the harder"
                                    + " failure where the 3x3 chunk ring is incomplete and the vehicle"
                                    + " is made static in the Bullet world; passenger_gate = a remote"
                                    + " passenger's missing chunk braking the driver;"
                                    + " player_square_null = the on-foot equivalent, where"
                                    + " IsoMovingObject rewinds the character to its last square every"
                                    + " tick.")
                    .labelNames("mechanism")
                    .register(StormPrometheus.registry());

    private static final Counter STALL_SECONDS =
            Counter.builder()
                    .name("storm_client_chunk_stall_seconds_total")
                    .help(
                            "Wall-clock seconds each mechanism was engaged. This is the metric to"
                                    + " optimise: seconds of a drive spent unable to accelerate."
                                    + " Divide by storm_client_chunk_stall_events_total for the mean"
                                    + " episode length, and rate() it for the fraction of playtime"
                                    + " lost to chunk stalls.")
                    .labelNames("mechanism")
                    .register(StormPrometheus.registry());

    private static final Gauge STALL_ACTIVE =
            Gauge.builder()
                    .name("storm_client_chunk_stall_active")
                    .help(
                            "1 while the mechanism is currently engaged. Instantaneous companion to"
                                    + " storm_client_chunk_stall_seconds_total, for correlating a"
                                    + " stall against queue depth and speed on the same timeline.")
                    .labelNames("mechanism")
                    .register(StormPrometheus.registry());

    private static final Histogram STALL_DURATION =
            Histogram.builder()
                    .name("storm_client_chunk_stall_duration_seconds")
                    .help(
                            "Length of each completed stall episode. The distribution matters more"
                                    + " than the mean: a tail beyond 8 seconds implicates the"
                                    + " streamer's fixed 8-second resend timeout, which has no backoff"
                                    + " and discards any reply that arrives after it fires.")
                    .labelNames("mechanism")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram LATENCY =
            Histogram.builder()
                    .name("storm_client_chunk_latency_seconds")
                    .help(
                            "End-to-end age of a chunk when it lands in the world: from"
                                    + " ChunkRequest.time, stamped as the World Streamer puts the"
                                    + " request on the wire, to the vanilla LoadChunk event at the end"
                                    + " of IsoChunk.doLoadGridsquare. This is the number a streaming"
                                    + " fix has to move — every other client series is a proxy for it."
                                    + " Two caveats. Send times are harvested by the per-frame in-flight"
                                    + " walk, so a chunk answered before the next frame is never"
                                    + " observed at all; the histogram therefore covers requests that"
                                    + " lived at least one frame, and its count over a window is well"
                                    + " below arrivals_total by design. And a chunk re-requested after"
                                    + " the 8-second timeout keeps its original send time, so the"
                                    + " observation is total time the player waited for that terrain"
                                    + " rather than the age of the attempt that happened to succeed.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Gauge OLDEST_INFLIGHT =
            Gauge.builder()
                    .name("storm_client_chunk_oldest_inflight_seconds")
                    .help(
                            "Age of the oldest unanswered request at sample time, 0 when nothing is in"
                                    + " flight. Unlike the latency histogram this cannot be biased by"
                                    + " chunks that never arrive, which makes it the honest live"
                                    + " stall indicator: it climbs for exactly as long as the server"
                                    + " stays silent. Crossing 8 seconds means the streamer's resend"
                                    + " timer is about to fire and discard whatever reply is in transit.")
                    .register(StormPrometheus.registry());

    private static final Gauge INFLIGHT_TIMED_OUT =
            Gauge.builder()
                    .name("storm_client_chunk_inflight_timed_out")
                    .help(
                            "In-flight requests whose 8-second timeout has already fired, i.e."
                                    + " ChunkRequest.flagsWs has bit 3 set. These have been re-queued"
                                    + " for a fresh request and, critically, any reply to the original"
                                    + " is now discarded on arrival, so each one is a chunk the server"
                                    + " may well have paid to compress and send for nothing.")
                    .register(StormPrometheus.registry());

    private static final Counter TIMEOUTS =
            Counter.builder()
                    .name("storm_client_chunk_timeouts_total")
                    .help(
                            "Requests observed crossing the 8-second timeout, counted once per attempt"
                                    + " on the rising edge — a chunk that times out four times before"
                                    + " it lands counts four. The timeout is flat with no backoff, so a"
                                    + " server that is merely slow rather than lossy still produces"
                                    + " these, and each one adds a duplicate request to a queue that is"
                                    + " already the bottleneck. Compare against"
                                    + " storm_client_chunk_arrivals_total: a rising ratio is the"
                                    + " signature of the resend loop feeding itself.")
                    .register(StormPrometheus.registry());

    /**
     * Send times of requests seen in flight, keyed by packed chunk coordinate, so {@link
     * #onChunkLoaded} can age a chunk that the streamer has already recycled. Values are {@code
     * {firstSendTimeMillis, timeoutCounted, lastSeenSendTimeMillis}}; the last two dedupe {@link
     * #TIMEOUTS} per attempt without a second collection.
     *
     * <p>Concurrent because the two ends run on different threads: {@link #scanInFlight} fills it
     * from the sampler on the main thread, while {@code LoadChunk} — and so {@link #onChunkLoaded}
     * — fires on the World Streamer thread for Convert and SoftReset jobs, which reach {@code
     * IsoChunk.doLoadGridsquare} directly instead of queueing onto the main-thread drain. Slot 0 is
     * written inside the mapping function so it is never mutated after publication; slots 1 and 2
     * are only ever touched by the sampler.
     */
    private static final ConcurrentHashMap<Long, long[]> SEND_TIMES = new ConcurrentHashMap<>();

    /**
     * Entries are dropped past this age; a chunk cancelled in flight never arrives to clear one.
     */
    private static final long SEND_TIME_TTL_MILLIS = 120_000L;

    private static final int SEND_TIMES_SWEEP_AT = 4096;

    private static final long SEND_TIMES_SWEEP_INTERVAL_MILLIS = 10_000L;

    private static long lastSweepMillis;

    private static final String[] MECHANISMS = {
        "invalid_chunk_ahead",
        "invalid_chunk_behind",
        "brake_forced",
        "physics_disabled",
        "passenger_gate",
        "player_square_null",
    };

    private static final boolean[] ENGAGED = new boolean[MECHANISMS.length];
    private static final long[] ENGAGED_SINCE = new long[MECHANISMS.length];

    private static final String[] QUEUE_STAGES = {
        "hydration",
        "wanted",
        "in_flight",
        "in_flight_net",
        "sent_handoff",
        "cancel_pending",
        "main_to_ws",
        "ws_to_main",
    };

    private static final int STAGE_HYDRATION = 0;
    private static final int STAGE_WANTED = 1;
    private static final int STAGE_IN_FLIGHT = 2;
    private static final int STAGE_IN_FLIGHT_NET = 3;
    private static final int STAGE_SENT_HANDOFF = 4;
    private static final int STAGE_CANCEL_PENDING = 5;
    private static final int STAGE_MAIN_TO_WS = 6;
    private static final int STAGE_WS_TO_MAIN = 7;

    /**
     * Resolved once instead of per frame. Holding the children also pins every label value into the
     * registry at zero from startup: a series that only appears on its first stall makes "no stalls
     * yet" and "sampler never ran" scrape identically, and breaks any rate() spanning the first
     * one.
     */
    private static final GaugeDataPoint[] STALL_ACTIVE_BY_MECHANISM =
            new GaugeDataPoint[MECHANISMS.length];

    private static final CounterDataPoint[] STALL_SECONDS_BY_MECHANISM =
            new CounterDataPoint[MECHANISMS.length];

    private static final CounterDataPoint[] STALL_EVENTS_BY_MECHANISM =
            new CounterDataPoint[MECHANISMS.length];

    private static final DistributionDataPoint[] STALL_DURATION_BY_MECHANISM =
            new DistributionDataPoint[MECHANISMS.length];

    private static final GaugeDataPoint[] QUEUE_DEPTH_BY_STAGE =
            new GaugeDataPoint[QUEUE_STAGES.length];

    static {
        for (int i = 0; i < MECHANISMS.length; i++) {
            STALL_ACTIVE_BY_MECHANISM[i] = STALL_ACTIVE.labelValues(MECHANISMS[i]);
            STALL_SECONDS_BY_MECHANISM[i] = STALL_SECONDS.labelValues(MECHANISMS[i]);
            STALL_EVENTS_BY_MECHANISM[i] = STALL_EVENTS.labelValues(MECHANISMS[i]);
            STALL_DURATION_BY_MECHANISM[i] = STALL_DURATION.labelValues(MECHANISMS[i]);
            STALL_ACTIVE_BY_MECHANISM[i].set(0.0);
        }
        for (int i = 0; i < QUEUE_STAGES.length; i++) {
            QUEUE_DEPTH_BY_STAGE[i] = QUEUE_DEPTH.labelValues(QUEUE_STAGES[i]);
            QUEUE_DEPTH_BY_STAGE[i].set(0.0);
        }
    }

    /** Discard arrivals stamped longer ago than this; the stamp is assumed orphaned. */
    private static final long LATENCY_MAX_AGE_MILLIS = 60_000L;

    /** A gap this long means the sampler stopped — a menu visit, a load screen, a freeze. */
    private static final double MAX_SAMPLE_GAP_SECONDS = 3.0;

    private static WorldStreamer lastStreamer;

    /** Held between strides so the passenger-gate edge keeps its state on unsampled frames. */
    private static boolean lastPassengerGated;

    private static Field chunkRequests0;
    private static Field chunkRequests1;
    private static Field pendingRequests;
    private static Field pendingRequests1;
    private static Field sentRequests;
    private static Field waitingToCancelQ;
    private static Field mainThreadRequestQueue;
    private static Field tempRequests;
    private static Field requestingLargeArea;
    private static Field requestNumber;
    private static Field requestTime;
    private static Field requestFlagsWs;
    private static Field disableSimulation;
    private static Field connectionLostField;

    private static boolean resolved;
    private static boolean failed;
    private static int frame;
    private static int lastRequestNumber = -1;
    private static long lastSampleNanos;

    private ClientChunkStreamMetrics() {}

    /**
     * Runs on the main thread and on the World Streamer thread — {@code DoChunkAlways} reaches
     * {@code doLoadGridsquare} directly for Convert and SoftReset jobs. It therefore needs its own
     * fence: {@code StormEventDispatcher} catches {@code RuntimeException} only, so an {@code
     * Error} here would escape into the frame, and its handler logs per dispatch, which at
     * chunk-arrival rates is its own outage.
     */
    @SubscribeEvent
    public static void onChunkLoaded(LoadChunkEvent event) {
        if (failed) {
            return;
        }
        try {
            ARRIVALS.inc();

            IsoChunk chunk = event.chunk;
            if (chunk == null) {
                return;
            }
            long[] entry = SEND_TIMES.remove(key(chunk.wx, chunk.wy));
            if (entry == null || entry[0] <= 0L) {
                return;
            }
            long age = System.currentTimeMillis() - entry[0];
            // A request cancelled in flight never arrives to clear its stamp. Drive back over that
            // chunk an hour later and the stale stamp would land in the histogram as an hour-long
            // download, which is the one number a streaming fix has to move.
            if (age >= 0L && age <= LATENCY_MAX_AGE_MILLIS) {
                LATENCY.observe(age / 1000.0);
            }
        } catch (Throwable t) {
            disable(t);
        }
    }

    /**
     * Called from {@link io.pzstorm.storm.advice.chunkstream.PacketLimitAdvice} when the client
     * drops an outgoing packet for exceeding {@code MaxPacketsPerSecond}.
     *
     * <p>Not gated on {@link #failed}: that latch belongs to the reflective sampler, and a
     * reflection failure there says nothing about whether this counter still works.
     */
    public static void recordSuppressedPacket(String type) {
        SUPPRESSED_PACKETS.labelValues(type).inc();
    }

    private static void disable(Throwable cause) {
        if (failed) {
            return;
        }
        failed = true;
        LOGGER.error("Client chunk stream sampling disabled after error", cause);
    }

    @SubscribeEvent
    public static void sample(OnTickEvenPausedEvent event) {
        if (failed) {
            return;
        }
        try {
            resolveFields();
            sampleInner();
        } catch (Throwable t) {
            disable(t);
        }
    }

    private static void sampleInner() throws Exception {
        // Every ClientServerMap-derived series is meaningless in singleplayer: isChunkLoaded
        // hard-returns false with no client, so a healthy SP session would report a permanent
        // full-grid server-cell hole. This sampler only measures a server's chunk stream.
        if (!GameClient.client) {
            if (lastSampleNanos != 0L) {
                resetWorldState();
            }
            return;
        }

        WorldStreamer streamer = WorldStreamer.instance;
        if (streamer != lastStreamer) {
            resetWorldState();
            lastStreamer = streamer;
        }

        long now = System.nanoTime();
        double elapsed = lastSampleNanos == 0L ? 0.0 : (now - lastSampleNanos) / 1e9;
        lastSampleNanos = now;
        // OnTickEvenPaused stops firing outside the in-game state, so a menu visit shows up here as
        // one enormous frame. Charging that to whichever mechanism happened to be engaged at exit
        // would book minutes of fake stall in a single sample.
        if (elapsed > MAX_SAMPLE_GAP_SECONDS) {
            resetWorldState();
            elapsed = 0.0;
        }

        boolean scan = frame++ % SCAN_STRIDE == 0;
        // Not strided. sampleStreamer is the only thing that stamps a send time, and a chunk that
        // answers inside the stride window would never be stamped — so striding it would silently
        // drop every fast chunk from the latency histogram and leave only the slow tail.
        if (streamer != null) {
            sampleStreamer(streamer);
        }

        int hydration = IsoChunk.loadGridSquare.size();
        QUEUE_DEPTH_BY_STAGE[STAGE_HYDRATION].set(hydration);
        // Integer division on purpose: IsoChunkMap.update computes the insert budget as
        // 1 + count * 3 / chunkGridWidth, and a fractional gauge would not match what it inserts.
        HYDRATION_BUDGET.set(
                hydration == 0 ? 0 : 1 + hydration * 3 / Math.max(1, IsoChunkMap.chunkGridWidth));
        GRID_WIDTH.set(IsoChunkMap.chunkGridWidth);

        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            clearStalls(elapsed);
            VEHICLE_SPEED.set(0.0);
            return;
        }
        sampleStalls(player, elapsed, scan);

        if (scan) {
            scanAhead(player);
            scanHoles(player);
        }
    }

    /**
     * Drop everything tied to one world. Leaving to the menu stops {@code OnTickEvenPaused} mid-
     * state, so without this a mechanism engaged at exit stays pinned at 1 for the whole menu
     * session and its eventual close observes an episode made mostly of menu time.
     */
    private static void resetWorldState() {
        for (int i = 0; i < MECHANISMS.length; i++) {
            ENGAGED[i] = false;
            STALL_ACTIVE_BY_MECHANISM[i].set(0.0);
        }
        SEND_TIMES.clear();
        lastSampleNanos = 0L;
        lastRequestNumber = -1;
        VEHICLE_SPEED.set(0.0);
        GRID_HOLES.set(0.0);
        SERVER_CELL_HOLES.set(0.0);
        OLDEST_INFLIGHT.set(0.0);
        INFLIGHT_TIMED_OUT.set(0.0);
        STREAMER_BUSY.set(0.0);
        LARGE_AREA.set(0.0);
        HYDRATION_BUDGET.set(0.0);
        for (GaugeDataPoint stage : QUEUE_DEPTH_BY_STAGE) {
            stage.set(0.0);
        }
    }

    private static void sampleStreamer(WorldStreamer streamer) throws Exception {
        QUEUE_DEPTH_BY_STAGE[STAGE_WANTED].set(size(chunkRequests1, streamer));
        QUEUE_DEPTH_BY_STAGE[STAGE_IN_FLIGHT].set(size(pendingRequests1, streamer));
        QUEUE_DEPTH_BY_STAGE[STAGE_IN_FLIGHT_NET].set(size(pendingRequests, streamer));
        QUEUE_DEPTH_BY_STAGE[STAGE_SENT_HANDOFF].set(size(sentRequests, streamer));
        QUEUE_DEPTH_BY_STAGE[STAGE_CANCEL_PENDING].set(size(waitingToCancelQ, streamer));
        QUEUE_DEPTH_BY_STAGE[STAGE_MAIN_TO_WS].set(size(chunkRequests0, streamer));
        QUEUE_DEPTH_BY_STAGE[STAGE_WS_TO_MAIN].set(size(mainThreadRequestQueue, streamer));

        STREAMER_BUSY.set(streamer.isBusy() ? 1 : 0);
        LARGE_AREA.set(bool(requestingLargeArea, streamer) ? 1 : 0);

        // tempRequests is a scratch list left holding the batch updateMain last sent, so counting
        // it is only valid while updateMain is still running. Two states freeze the batch while
        // this sampler keeps firing: GameClient.update skips updateMain once connectionLost is
        // set, and a server pause (StartPausePacket) makes GameWindow skip states.update() while
        // still firing OnTickEvenPaused every frame. Without both guards the frozen batch would be
        // re-booked at frame rate for as long as the client sat there.
        if (!connectionLost() && !GameClient.IsClientPaused()) {
            int cancelled = size(tempRequests, streamer);
            if (cancelled > 0) {
                CANCELS.inc(cancelled);
            }
        }

        if (requestNumber != null) {
            int current = requestNumber.getInt(streamer);
            if (lastRequestNumber >= 0 && current > lastRequestNumber) {
                REQUESTS.inc(current - lastRequestNumber);
            }
            lastRequestNumber = current;
        }

        scanInFlight(streamer);
    }

    /**
     * Walks the in-flight request list to harvest send times, the oldest outstanding age, and the
     * timeout flag. This is the only place that reads the elements of a streamer list rather than
     * its size, and the list belongs to the World Streamer thread: the walk indexes it by position
     * and treats any exception, shrinking size, or null element as "the list moved under us" and
     * stops, since a partial sample of a racing list is fine and a thrown frame is not.
     */
    private static void scanInFlight(WorldStreamer streamer) throws Exception {
        if (pendingRequests1 == null || requestTime == null) {
            return;
        }
        Object value = pendingRequests1.get(streamer);
        if (!(value instanceof List)) {
            return;
        }
        List<?> requests = (List<?>) value;

        long now = System.currentTimeMillis();
        long oldest = now;
        boolean sawAny = false;
        int timedOut = 0;
        try {
            for (int i = 0, n = requests.size(); i < n; i++) {
                Object element = requests.get(i);
                if (!(element instanceof WorldStreamer.ChunkRequest)) {
                    break;
                }
                WorldStreamer.ChunkRequest request = (WorldStreamer.ChunkRequest) element;
                IsoChunk chunk = request.chunk;
                if (chunk == null) {
                    continue;
                }
                long sent = requestTime.getLong(request);
                if (sent > 0L) {
                    sawAny = true;
                    if (sent < oldest) {
                        oldest = sent;
                    }
                }
                long stamped = sent;
                long[] entry =
                        SEND_TIMES.computeIfAbsent(
                                key(chunk.wx, chunk.wy), k -> new long[] {stamped, 0L, stamped});
                // resendTimedOutRequests retires the request and pushes the chunk back to
                // chunkRequests1, so a retry arrives as a fresh ChunkRequest with a fresh time.
                // Without rearming on that change the counter books one timeout per coordinate
                // forever and reports a chunk that has failed six times as a single failure — the
                // exact case that leaves a player stuck. entry[0] deliberately keeps the first
                // stamp, so latency stays the whole wait rather than the last attempt's.
                if (sent > 0L && sent != entry[2]) {
                    entry[2] = sent;
                    entry[1] = 0L;
                }
                if (requestFlagsWs != null && (requestFlagsWs.getInt(request) & 8) != 0) {
                    timedOut++;
                    if (entry[1] == 0L) {
                        entry[1] = 1L;
                        TIMEOUTS.inc();
                    }
                }
            }
        } catch (IndexOutOfBoundsException | NullPointerException ignored) {
            // the World Streamer thread mutated the list mid-walk; this sample is simply short
        }

        // sawAny, not requests.isEmpty(): a walk that aborted on the first recycled element would
        // otherwise report 0.0, which reads as an empty pipeline — the opposite of the truth.
        OLDEST_INFLIGHT.set(sawAny ? Math.max(0L, now - oldest) / 1000.0 : 0.0);
        INFLIGHT_TIMED_OUT.set(timedOut);
        sweepSendTimes(now);
    }

    /**
     * Age out orphaned stamps. Time-driven rather than size-driven: a size threshold alone spins,
     * because once the map is over the mark and nothing is old enough to drop, every subsequent
     * frame walks the whole map again and removes nothing.
     */
    private static void sweepSendTimes(long now) {
        if (now - lastSweepMillis < SEND_TIMES_SWEEP_INTERVAL_MILLIS
                && SEND_TIMES.size() < SEND_TIMES_SWEEP_AT) {
            return;
        }
        lastSweepMillis = now;
        Iterator<Map.Entry<Long, long[]>> it = SEND_TIMES.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue()[0] > SEND_TIME_TTL_MILLIS) {
                it.remove();
            }
        }
    }

    private static long key(int wx, int wy) {
        return ((long) wx << 32) ^ (wy & 0xFFFFFFFFL);
    }

    private static void sampleStalls(IsoPlayer player, double elapsed, boolean scan)
            throws Exception {
        BaseVehicle vehicle = player.getVehicle();
        if (vehicle == null) {
            VEHICLE_SPEED.set(0.0);
            for (int i = 0; i < MECHANISMS.length - 1; i++) {
                edge(i, false, elapsed);
            }
            edge(MECHANISMS.length - 1, player.getCurrentSquare() == null, elapsed);
            return;
        }
        VEHICLE_SPEED.set(vehicle.getCurrentSpeedKmHour());

        // isInvalidChunkAround dereferences vehicle.chunk without a null check; vanilla only
        // reaches it from CarController while the local driver is on the pedals, by which point
        // the field is set. Sampling every frame does not have that guarantee.
        boolean ahead = vehicle.chunk != null && vehicle.isInvalidChunkAhead();
        boolean behind = vehicle.chunk != null && vehicle.isInvalidChunkBehind();
        CarController controller = vehicle.getController();
        boolean braking = controller != null && controller.isBrakePedalPressed();

        edge(0, ahead, elapsed);
        edge(1, behind, elapsed);
        edge(2, ahead && braking, elapsed);
        edge(3, bool(disableSimulation, vehicle), elapsed);
        if (scan) {
            lastPassengerGated = passengerGated(vehicle);
        }
        edge(4, lastPassengerGated, elapsed);
        edge(5, player.getCurrentSquare() == null, elapsed);
    }

    /**
     * True when a remote passenger's missing chunk is what makes the surrounding area invalid — the
     * local client has the chunk and the server agrees, yet the passenger mask refuses it.
     *
     * <p>All three terms are needed. {@code BaseVehicle.isNullChunk} ORs the local chunk, the
     * server mirror and the passenger mask together, so without the local-chunk term this counts
     * every chunk the local client is simply missing and attributes it to the passenger gate.
     */
    private static boolean passengerGated(BaseVehicle vehicle) {
        int wx = chunkOf(vehicle.getX());
        int wy = chunkOf(vehicle.getY());
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = wx + dx;
                int cy = wy + dy;
                if (!IsoWorld.instance.getMetaGrid().isValidChunk(cx, cy)) {
                    continue;
                }
                if (IsoWorld.instance.currentCell.getChunk(cx, cy) != null
                        && ClientServerMap.isChunkLoaded(cx, cy)
                        && !PassengerMap.isChunkLoaded(vehicle, cx, cy)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void scanAhead(IsoPlayer player) {
        int wx = chunkOf(player.getX());
        int wy = chunkOf(player.getY());
        int loaded = 0;
        int serverCellMissing = 0;
        int clientChunkMissing = 0;
        int bothMissing = 0;
        for (int dy = -AHEAD_RADIUS; dy <= AHEAD_RADIUS; dy++) {
            for (int dx = -AHEAD_RADIUS; dx <= AHEAD_RADIUS; dx++) {
                int cx = wx + dx;
                int cy = wy + dy;
                if (!IsoWorld.instance.getMetaGrid().isValidChunk(cx, cy)) {
                    continue;
                }
                boolean cell = ClientServerMap.isChunkLoaded(cx, cy);
                boolean chunk = IsoWorld.instance.currentCell.getChunk(cx, cy) != null;
                if (cell && chunk) {
                    loaded++;
                } else if (cell) {
                    clientChunkMissing++;
                } else if (chunk) {
                    serverCellMissing++;
                } else {
                    bothMissing++;
                }
            }
        }
        AHEAD_SAMPLES.labelValues("loaded").inc(loaded);
        AHEAD_SAMPLES.labelValues("client_chunk_missing").inc(clientChunkMissing);
        AHEAD_SAMPLES.labelValues("server_cell_missing").inc(serverCellMissing);
        AHEAD_SAMPLES.labelValues("both_missing").inc(bothMissing);
    }

    private static void scanHoles(IsoPlayer player) {
        IsoChunkMap chunkMap = IsoWorld.instance.currentCell.getChunkMap(player.getIndex());
        int holes = 0;
        if (chunkMap != null && !chunkMap.ignore) {
            for (int y = 0; y < IsoChunkMap.chunkGridWidth; y++) {
                for (int x = 0; x < IsoChunkMap.chunkGridWidth; x++) {
                    if (chunkMap.getChunk(x, y) == null) {
                        holes++;
                    }
                }
            }
        }
        GRID_HOLES.set(holes);

        int cellHoles = 0;
        ClientServerMap mirror = GameClient.loadedCells[player.getIndex()];
        if (mirror != null && mirror.loaded != null) {
            for (boolean flag : mirror.loaded) {
                if (!flag) {
                    cellHoles++;
                }
            }
        }
        SERVER_CELL_HOLES.set(cellHoles);
    }

    private static void clearStalls(double elapsed) {
        for (int i = 0; i < MECHANISMS.length; i++) {
            edge(i, false, elapsed);
        }
    }

    private static void edge(int index, boolean active, double elapsed) {
        if (active) {
            if (!ENGAGED[index]) {
                ENGAGED[index] = true;
                ENGAGED_SINCE[index] = System.nanoTime();
                STALL_EVENTS_BY_MECHANISM[index].inc();
                STALL_ACTIVE_BY_MECHANISM[index].set(1.0);
            }
            if (elapsed > 0.0) {
                STALL_SECONDS_BY_MECHANISM[index].inc(elapsed);
            }
        } else if (ENGAGED[index]) {
            ENGAGED[index] = false;
            STALL_ACTIVE_BY_MECHANISM[index].set(0.0);
            STALL_DURATION_BY_MECHANISM[index].observe(
                    (System.nanoTime() - ENGAGED_SINCE[index]) / 1e9);
        }
    }

    private static int chunkOf(float coord) {
        return Math.floorDiv((int) Math.floor(coord), 8);
    }

    private static int size(Field field, Object owner) throws Exception {
        if (field == null) {
            return 0;
        }
        Object value = field.get(owner);
        return value instanceof Collection ? ((Collection<?>) value).size() : 0;
    }

    private static boolean bool(Field field, Object owner) throws Exception {
        return field != null && field.getBoolean(owner);
    }

    private static void resolveFields() throws Exception {
        if (resolved) {
            return;
        }
        resolved = true;
        chunkRequests0 = open(WorldStreamer.class, "chunkRequests0");
        chunkRequests1 = open(WorldStreamer.class, "chunkRequests1");
        pendingRequests = open(WorldStreamer.class, "pendingRequests");
        pendingRequests1 = open(WorldStreamer.class, "pendingRequests1");
        sentRequests = open(WorldStreamer.class, "sentRequests");
        waitingToCancelQ = open(WorldStreamer.class, "waitingToCancelQ");
        mainThreadRequestQueue = open(WorldStreamer.class, "mainThreadRequestQueue");
        tempRequests = open(WorldStreamer.class, "tempRequests");
        requestingLargeArea = open(WorldStreamer.class, "requestingLargeArea");
        requestNumber = open(WorldStreamer.class, "requestNumber");
        requestTime = open(WorldStreamer.ChunkRequest.class, "time");
        requestFlagsWs = open(WorldStreamer.ChunkRequest.class, "flagsWs");
        disableSimulation =
                open(BaseVehicle.class, "disableSimulationDueToLackOfSurroundingChunks");
        connectionLostField = open(GameClient.class, "connectionLost");
    }

    /** Unreadable field means "assume connected" — losing a guard beats losing the counter. */
    private static boolean connectionLost() throws Exception {
        GameClient instance = GameClient.instance;
        if (connectionLostField == null || instance == null) {
            return false;
        }
        return connectionLostField.getBoolean(instance);
    }

    private static Field open(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            LOGGER.warn(
                    "Client chunk metrics: {}.{} unavailable, that series will read 0",
                    owner.getSimpleName(),
                    name);
            return null;
        }
    }
}
