# Chunk streaming observability

How to tell *why* a player outran the chunk stream, and how to prove a fix worked.

The reported symptom is specific: **a player driving fast gets stuck and cannot progress.** This
document maps that symptom onto the pipeline, names the metric that measures each stage, and gives a
decision procedure for attributing a given stall to one of five distinct causes — because the fix for
each is different, and only three of them originate on the server.

## The pipeline

```
                     CLIENT                                        SERVER
  ProcessChunkPos recentres the grid up to
  speed/5 tiles ahead (uncapped for a driver)
                  |
                  v
  WorldStreamer.chunkRequests1        [wanted]
   World Streamer thread: 20ms while busy,
   280ms while idle  <-- latency floor
                  |
                  v
  RequestZipListPacket (one packet, uncapped)  --------->  RequestZipListPacket.parse
                  |                                        splits into 20-chunk ClientChunkRequests
                  v                                                    |
  WorldStreamer.pendingRequests1      [in_flight]                      v
   8s flat resend timeout, no backoff                    PlayerDownloadServer.ccrWaiting  [backlog]
                  ^                                       ONE request dispatched per connection
                  |                                       per 10Hz tick, and only if that
                  |                                       connection's single worker is idle
                  |                                                    |
                  |                                                    v
                  |                                       worker: read save file -> deflate -> send
                  |                                        (no save file = retry ladder, 3 strikes,
                  |                                         then NotRequiredInZip with no data)
                  |                                                    |
                  |                                                    v
                  |                                       RakNet HIGH send queue        [wire]
                  |                                        one chunk = ceil(bytes/1000)
                  |                                        RELIABLE messages, draining at
                  |                                        whatever BPS congestion control allows
                  |                                                    |
   SentChunkPacket <-------------------------------------------------- +
                  |
                  v
  decompress (World Streamer thread)
                  |
                  v
  IsoChunk.loadGridSquare             [hydration]
   main thread drains 1 + depth*3/chunkGridWidth per frame  <-- the only per-frame cap
                  |
                  v
  chunk in the world -> LuaEventManager "LoadChunk"
```

Independent of all of that, the server has to have the chunk in memory before it can serve it. A
chunk is unreachable until its entire 64-chunk `ServerCell` has been through three single-threaded
stages (`LoadChunk` -> `RecalcAll` -> main-thread `Load2`) with **no budget or cap at any stage**.
The download worker cannot generate terrain; for never-visited ground it just fails to find a save
file and enters the retry ladder.

## The hard ceiling

One `ClientChunkRequest` per connection per tick, at most 20 chunks per request, at 10 Hz:

**200 chunks/second/player, absolute maximum.** A chunk is 8x8 tiles, so that is 12800 tiles/s of
new terrain — but it is spent on a 13x13 chunk grid that recentres in whole rows. Demand has no cap
at all: `WorldStreamer.updateMain()` packs everything the client wants into one packet, and
`ProcessChunkPos` pushes the wanted-set `speed/5` tiles ahead of a driver with no upper bound (100
km/h = 20 tiles, 160 km/h = 32 tiles).

That asymmetry is the whole problem. Everything below is about finding which part of it bites first.

## Six causes, six different fixes

The player experiences one thing — "I can't move". These are six unrelated mechanisms, and the
metrics separate them.

| # | Cause | Where | Fix direction | Metric that proves it |
|---|---|---|---|---|
| 1 | Server dispatch ceiling — backlog grows, worker idle | Server | Raise dispatch rate | `storm_chunk_stream_worker_samples_total{state="ready_backlogged"}`, `storm_chunk_stream_queue_wait_seconds{kind="fresh"}` |
| 2 | Server worker saturated — disk, deflate, wire | Server | Cheaper serialization | `storm_chunk_stream_worker_samples_total{state="busy"}` + `pz_chunk_save_loaded_call_duration_seconds{caller="download"}` |
| 3 | Terrain not hydrated server-side — retry ladder, then empty reply | Server | Budget + prioritise cell hydration | `storm_chunk_stream_request_residency_total{state!="resident"}` (`state="cell_absent"` for the stuck half), `storm_chunk_stream_retry_exhausted_total`, `storm_chunk_hydration_oldest_pending_seconds` |
| 4 | Client hydration budget — chunks arrived but aren't in the world | Client | Raise the per-frame drain | `storm_client_chunk_queue_depth{stage="hydration"}` |
| 5 | Coarse server-cell mirror braking a car over terrain it already has | Both | Finer-grained `isNullChunk` | `storm_chunk_stream_peer_cell_holes_max` (server, production), `storm_client_chunk_ahead_samples_total{state="server_cell_missing"}` (client, exact) |
| 6 | The wire — chunks compressed and handed to RakNet, still queued | Server | Rate-limit or reprioritise, or accept the link | `storm_peer_send_buffer_messages{priority="high"}`, `storm_peer_congestion_limited` |

Cause 5 deserves emphasis because it is pure bookkeeping loss. `BaseVehicle.isInvalidChunkAhead()`
does not consult the client's chunk map — it consults `ClientServerMap.isChunkLoaded()`, a mirror of
the server's **64x64-tile** `ServerCell` flags. One boolean covers 8x8 chunks. So a car can be
force-braked (transmission to neutral, zero engine force, throttle decaying) while every chunk under
and around it is fully loaded and rendered, purely because the cell flag has not arrived. The
lookahead is a fixed 16 tiles regardless of speed, which at 100 km/h is well under a second of
warning.

## Decision procedure

Run these in order. The first one that fires is the binding constraint.

**0. Is anyone actually stuck?** The symptom itself, on production, with no client instrumentation:

```promql
max_over_time(storm_chunk_stream_peer_brake_seconds_max[$__rate_interval])
storm_chunk_stream_peer_brake_seconds
```

`peer_brake_seconds` is how long each peer's *current* brake episode has been running, keyed by
username; the `_max` is the worst player on the server. Crossing a cell boundary normally costs a few
hundred milliseconds. Multi-second episodes are the reported bug, and a value that keeps climbing is
a player who is not getting out of it. Everything below attributes those seconds to a cause.

**0b. Who is *about* to be stuck?** Everything else here is measured after the player has already hit
the wall. This is the margin in front of them:

```promql
storm_chunk_stream_runway_tiles / storm_chunk_stream_speed_tiles_per_second
  and storm_chunk_stream_speed_tiles_per_second > 1
```

Seconds of already-hydrated world ahead of each moving driver. Compare it against what one cell costs
to hydrate — `histogram_quantile(0.9, rate(storm_chunk_hydration_cell_total_seconds[5m]))`. When the
runway is shorter than that, the driver stalls no matter how fast the download side runs, because the
world in front of them does not exist on the server yet; the fix is upstream of the stream entirely
(warmer reach, lookahead), not in dispatch or worker throughput. Note this reads `ServerMap`, not the
peer's mirror — it is the complement of `peer_brake_cells`, which measures what the client has been
*told*. Gate on the speed series: a parked or on-foot peer reports the 512-tile cap and 0 speed, so
the ratio is `+Inf` by construction and means nothing.

**Is the server even the problem?**

```promql
sum(rate(storm_chunk_stream_sent_total[$__rate_interval]))
  /
sum(rate(storm_chunk_stream_requested_total[$__rate_interval]))
```

Near 1.0 with a flat `storm_chunk_stream_backlog_chunks_max` means the server is keeping up and the
stall is client-side — go to the client questions.

**1. Dispatch ceiling vs worker saturation.** These are mutually exclusive and the split is the
single most useful number in the whole set:

```promql
sum(rate(storm_chunk_stream_worker_samples_total{state="ready_backlogged"}[$__rate_interval]))
sum(rate(storm_chunk_stream_worker_samples_total{state="busy"}[$__rate_interval]))
```

`ready_backlogged` dominant: the worker was free, work was queued, and the one-request-per-tick rule
refused to dispatch it. Raising the dispatch rate helps directly.

`busy` dominant: the worker never had a free tick. Raising the dispatch rate does nothing — it just
moves the queue. Attack serialization cost instead.

Confirm the split with the queue-wait histogram, which separates delay from work:

```promql
histogram_quantile(0.95, sum by (le, kind) (rate(storm_chunk_stream_queue_wait_seconds[$__rate_interval])))
```

This is the gap between a `ClientChunkRequest` being allocated for a peer and its worker starting on
it. `kind="fresh"` is demand straight off the wire; `kind="retry"` is a rung of the three-strike
ladder, which by construction waits at least a tick longer. A p95 of several hundred milliseconds on
`fresh` with an idle worker is the one-request-per-tick rule, restated in seconds instead of sample
counts. Compare it against `storm_chunk_stream_batch_duration_seconds`: whichever is larger is the
half worth optimising.

**2. Is terrain missing rather than slow?**

```promql
sum(rate(storm_chunk_stream_request_residency_total{state!="resident"}[$__rate_interval]))
  /
sum(rate(storm_chunk_stream_request_residency_total[$__rate_interval]))
```

This is answerability measured **at the moment the request arrives**: could `ServerMap` have served
it right then, or did the client ask for ground the server does not have hydrated? Read it against
the same ratio for `storm_chunk_stream_dispatched_total{source="cold"}`, which is measured later, at
dispatch. The gap between the two is demand that hydration caught up with while the request sat in
the queue — pure latency. If the two ratios are equal, waiting did not help and hydration is the
binding constraint, not scheduling.

Then split the non-resident share, because the three reasons want opposite fixes:

```promql
sum by (state) (rate(storm_chunk_stream_request_residency_total{state!="resident"}[$__rate_interval]))
```

`cell_loading` is a `ServerCell` that exists but has not finished hydrating: the request is early, a
retry will probably land, and the fix is faster hydration. `chunk_absent` is a loaded cell with that
one slot empty, or holding a chunk that is not itself loaded; it should be rare and it points at the
cell rather than at the stream. `cell_absent`
is no `ServerCell` at all — nothing is loading it, so the request burns all three retries and the
client waits out its flat 8-second resend timer for a chunk the server never even started. That is
the state that separates a player who is *stuck* from one who is merely waiting, and its fix is
getting the cell requested sooner (warmer reach, lookahead), not making hydration quicker:

```promql
sum(rate(storm_chunk_stream_request_residency_total{state="cell_absent"}[$__rate_interval]))
  /
sum(rate(storm_chunk_stream_request_residency_total[$__rate_interval]))
```

```promql
rate(storm_chunk_stream_retry_exhausted_total[$__rate_interval])
```

Non-zero means the server gave up after three attempts and sent `NotRequiredInZip` with no data. The
client keeps a hole there. This is the direct counter for "the driver hit an invisible wall the
server can never fill", and it points at hydration, not at streaming:

```promql
storm_chunk_hydration_cells_pending
storm_chunk_hydration_queue_depth
storm_chunk_hydration_oldest_pending_seconds
```

A rising `cells_pending` while `queue_depth{stage="load_in"}` is non-empty means the cell loader is
behind. Because a chunk is unreachable until its whole 64-chunk cell lands, one slow cell blackholes
4096 tiles. `oldest_pending_seconds` is the one to alert on: it is the only series that sees a cell
that never finishes, and a line that climbs without bound rather than sawtoothing is a cell stranded
by a swallowed exception in `ServerChunkLoader.run` — `startedLoading`/`doingRecalc` stay set, so
`preupdate` never re-dispatches it and `ServerMap.getChunk` answers null for its 64 chunks for the
rest of the server's life.

Then split hydration into its two single-threaded stages, because they need opposite fixes:

```promql
histogram_quantile(0.95, sum by (le, stage) (rate(storm_chunk_hydration_cell_duration_seconds[$__rate_interval])))
histogram_quantile(0.95, sum by (le, content) (rate(storm_chunk_hydration_cell_total_seconds[$__rate_interval])))
```

`stage="load"` is the single LoadChunk thread doing disk reads or worldgen; `stage="recalc"` is the
single max-priority RecalcAll thread plus the wait for the main thread to run `Load2`. The `content`
split says whether the cost is worldgen (`generated`) or a plain save-file read (`disk`) — a driver
heading into never-visited map is on a different curve from one retracing roads, and only the first
is fixable by pre-generating.

Finally, check how much hydration work is being thrown away:

```promql
rate(storm_chunk_hydration_cancelled_cells_total{stage="in_flight"}[$__rate_interval])
```

A driver outrunning hydration produces this continuously — cells requested, half-loaded, abandoned on
the way past, requested again on the way back. High `in_flight` means the two hydration threads are
busy on work nobody will ever see, which is *why* the cells actually ahead of the player stay
pending.

**3. Did the bytes actually leave?**

Everything above ends when the worker calls `SendRaw`. That is not the wire — it is RakNet's send
queue, and a saturated link parks chunks there while every upstream metric stays green:

```promql
topk(5, storm_peer_send_buffer_messages{priority="high"})
storm_peer_send_buffer_bytes{priority="high"} / storm_peer_bps_limit_congestion
storm_peer_congestion_limited
storm_peer_bandwidth_limited
```

`SentChunkPacket` fragments each compressed chunk into 1000-byte HIGH/RELIABLE messages, so the
message gauge at `priority="high"` is close to a literal count of chunk fragments queued for that
player. The ratio is the one to read first: **queued bytes divided by the allowed rate is how many
seconds of chunk data are stacked ahead of this player.** When that exceeds the time it takes to
cross a cell boundary at driving speed, the player outruns delivery no matter how fast the server
produced the data — and the fix is rate-limiting or reprioritising, not more throughput.

`congestion_limited` and `bandwidth_limited` say which ceiling is binding. Congestion is the link
backing off and is not something the server can fix; the outgoing-bandwidth cap is configuration and
can be raised. Note that the chunk send path bypasses `UdpConnection.endPacket`, so vanilla's
`sent-bytes` counters undercount chunk traffic — use `storm_chunk_stream_sent_bytes_total` for volume
and these gauges for pressure.

**4. Is a global lock the limiter?**

```promql
histogram_quantile(0.99, sum by (le, caller) (rate(storm_chunk_checksum_duration_seconds[$__rate_interval])))
histogram_quantile(0.99, sum by (le, caller) (rate(storm_chunk_disk_read_duration_seconds[$__rate_interval])))
```

`ChunkChecksum.getChecksum` wraps a whole file read in one static monitor shared by every peer, and
`IsoChunk.SafeRead` holds a per-chunk lock across its `FileInputStream`. The `caller` label splits
download workers from everyone else; a `download` p99 well above the `other` p99 means workers are
queueing behind each other rather than behind disk.

**5. Is dedupe eating the main thread?**

```promql
histogram_quantile(0.99, sum by (le) (rate(pz_player_download_dedupe_call_duration_seconds[$__rate_interval])))
```

`removeOlderDuplicateRequests` runs on the main thread once per connection per tick and costs
roughly O(backlog^2). This is a positive feedback loop: deeper backlog -> more main-thread time ->
slower dispatch -> deeper backlog. Watch it against `storm_chunk_stream_backlog_chunks_max`; if they
climb together the server is amplifying its own congestion.

**6. Client: which stage holds the backlog?**

```promql
storm_client_chunk_queue_depth
```

- `wanted` deep: the World Streamer thread hasn't turned demand into requests yet. Note its cadence
  is 20 ms while busy but **280 ms while idle**, so a newly wanted chunk can wait a quarter second
  before it is even asked for — a real latency floor at the start of every acceleration.
- `in_flight` deep: waiting on the server. Cross-check against the server metrics above.
- `hydration` deep: the chunks are here, decompressed, and the main-thread drain
  (`1 + depth*3/chunkGridWidth` per frame) is losing. Compare with
  `storm_client_chunk_hydration_budget`.

**7. Client: is the stall real or bookkeeping?**

```promql
sum(rate(storm_client_chunk_ahead_samples_total{state="server_cell_missing"}[$__rate_interval]))
  /
sum(rate(storm_client_chunk_ahead_samples_total[$__rate_interval]))
```

`server_cell_missing` means the client has the chunk and the coarse cell mirror says otherwise. Every
one of those samples is a brake the player did not need. A large share here says fix
`isNullChunk`/`ClientServerMap` granularity, not throughput.

This one does **not** require a scrapeable client. The server owns the mirror it pushes — 
`UdpConnection.getLoadedCell(playerIndex).loaded` is the same `boolean[]` the client's brake
consults — so `storm_chunk_stream_peer_cell_holes` measures it on production, from real players,
with no client changes at all:

```promql
max_over_time(storm_chunk_stream_peer_cell_holes_max[$__rate_interval])
```

The server only pushes the mirror on change, so the client's copy can be staler but never fresher:
the server-side count is a lower bound on what is actually braking the player. Use the client
series when a dev client is available and this one everywhere else.

**8. Client: how much play time is actually lost?**

```promql
sum(rate(storm_client_chunk_stall_seconds_total[$__rate_interval]))
```

This is the headline number: 0.1 means 10% of wall-clock time the player could not accelerate.
Broken out by `mechanism`, it says which of the four blocking paths to attack —
`brake_forced` (the `CarController` force-brake), `physics_disabled` (the harder failure where the
3x3 chunk ring is incomplete and the vehicle is made static in the Bullet world), `passenger_gate`
(a remote passenger's missing chunk braking the driver), or `player_square_null` (the on-foot
equivalent, where the character is rewound to its last square every tick).

A `storm_client_chunk_stall_duration_seconds` p99 tail beyond 8 seconds implicates the streamer's
fixed 8-second resend timeout, which has no backoff and **discards any reply that arrives after it
fires** — so a late chunk costs the bandwidth twice and the player waits another 8 seconds.

## Evaluating a change

The point of all of the above is that "it feels better" is not evidence. Protocol:

1. **Fix the workload.** Drive a scripted route at a fixed speed, or reuse the same route each run.
   Chunk demand is a function of speed and of whether the terrain was ever visited, so an unscripted
   run compares nothing. Exclude world load-in: `storm_client_chunk_requesting_large_area` is 1
   during initial load and teleport, and the streamer behaves differently in that mode (self-capped
   at 40 in-flight, cancellation disabled).
2. **Primary outcome:** `sum(rate(storm_client_chunk_stall_seconds_total[...]))` over the run. This
   is the thing the player feels. Everything else is a mechanism metric. Without a scrapeable client,
   the server-side stand-in is `max_over_time(storm_chunk_stream_peer_brake_seconds_max[...])` plus
   the count of episodes that got past a threshold — coarser, because it only sees the force-brake and
   not the other three blocking paths, but it is the same quantity in the same units and it works on
   production with real players.
3. **Primary mechanism:** `histogram_quantile(0.99, rate(storm_client_chunk_latency_seconds[...]))`
   — how long a chunk actually took from request to being in the world. This is the number a
   streaming fix is supposed to move, and the one that says whether a stall improvement came from
   faster delivery or from something else. Read it alongside
   `max(storm_client_chunk_oldest_inflight_seconds)`, which cannot be biased by chunks that never
   arrive and so is the honest ceiling.
4. **Secondary outcome:** delivered rate,
   `sum(rate(storm_chunk_stream_sent_total[...]))`, and the client's
   `rate(storm_client_chunk_arrivals_total[...])`. A change that raises throughput without lowering
   stall seconds moved the bottleneck rather than removing it.
5. **Guard against regression elsewhere.** Raising the dispatch rate spends main-thread time. Watch
   `storm_server_tick_duration_seconds` and the dedupe histogram; a chunk fix that adds 20 ms to the
   tick is a net loss on a full server.
6. **Check for waste, not just speed.** `rate(storm_client_chunk_timeouts_total)` is the direct
   measure — every one of those is a chunk the server may already have compressed and sent, whose
   reply is discarded on arrival. `rate(storm_client_chunk_requests_total)` minus
   `rate(storm_client_chunk_arrivals_total)` bounds the total re-request volume including cancels. A
   change can raise delivered chunks while raising waste faster.

## What is still not measured

Honest gaps, with the reason each was left alone.

- **Latency of chunks answered within a single frame.** `storm_client_chunk_latency_seconds` works by
  harvesting `ChunkRequest.time` during the per-frame in-flight walk and matching it to the chunk's
  `LoadChunk` arrival, because the request object itself is pooled and recycled the moment it
  completes. A chunk the server answers before the next frame is therefore never observed at all, so
  the histogram's count runs well below `arrivals_total` and its distribution is biased toward slow
  requests. That is the right bias for a stall investigation and the wrong one for claiming a median
  improved — quote the p99 and the count together, and use
  `storm_client_chunk_oldest_inflight_seconds` when an unbiased number is needed. Removing the bias
  entirely needs an `@Advice` on `WorldStreamer.loadReceivedChunks`.
- **Timeouts the sampler blinks past.** `storm_client_chunk_timeouts_total` counts requests seen
  carrying the timeout flag. The flag is set and the chunk re-queued within one 20 ms streamer pass,
  so a timeout that resolves between two frame samples is missed; the counter is a floor, not an
  exact count. `requests_total - arrivals_total` remains the upper bound.
- **Received chunk bytes on the client.** `SentChunk` short-circuits in `GameClient.addIncoming`
  before reaching `onClientPacket`, so vanilla's `NetworkStatistic` never counts incoming chunk
  traffic and there is no field to sample. Connection-wide
  `ZNetStatistics.totalActualBytesReceived` is the only free approximation.
- **Sub-queue detail inside RakNet.** The per-connection snapshot exposes queue depth per *priority*
  but not per ordering channel, and there are no split-packet or datagram-history counters and no
  send receipts, so a chunk cannot be followed individually once it is handed to `SendRaw`.
  `RakNetPeerInterface.sendLock` would show main-thread send serialization, but it is package-private
  (reflection), global rather than per-peer, and — decisively — the chunk path bypasses it, so it
  would say nothing about chunk backpressure.
- **Runway for players not in a vehicle.** `storm_chunk_stream_runway_tiles` needs a heading, and the
  only speed vector the server has for free is `BaseVehicle.jniLinearVelocity`, written from
  `VehiclePhysicsPacket.processServer`. On foot there is no equivalent field — it would mean
  differencing player positions across ticks and keeping per-player state — and a walking player
  cannot outrun the stream anyway, so both the runway and the speed read as "no risk" (cap and 0).
  The vehicle number itself is also only as fresh as that packet's 150 ms cadence (300 ms parked), so
  a car that has just turned hard reports the previous heading for up to a frame or two of samples.
- **Runway is cell-resolution and ray-thin.** One `ServerCell` flag covers 64x64 tiles, and the march
  samples every 16 tiles along a single ray, so it cannot see a hole the driver is about to swerve
  into and a ray that only clips a cell corner can miss that cell entirely. It answers "is the road
  straight ahead hydrated", not "is the neighbourhood hydrated".
- ~~**Silent request loss.**~~ Now measured, as
  `storm_client_packet_suppressed_total{type="RequestZipList"}`. `PacketType.send` cancels the
  packet when `MaxPacketsPerSecond` is exceeded, but `WorldStreamer.updateMain` has already added
  the requests to `sentRequests` — the client believes it asked, and those chunks only recover via
  the flat 8-second resend timer. `PacketsCache` keeps a sliding one-second window and no
  cumulative count, so this needed a patch (`PacketLimitMetricsPatch`, exit-only advice on
  `PacketsCache.isLimitExceeded`, gated on `GameClient.client` because only the client acts on the
  result). Remaining caveat: it says a request was lost, not which chunks were in it.

## Where the metrics live

| | Class | Registered by |
|---|---|---|
| Server, per-peer backlog and delivery | `io.pzstorm.storm.metrics.ChunkStreamMetrics` | `ServerTickAdvice` + chunk-path patches |
| Server, world hydration depth and latency | `io.pzstorm.storm.metrics.ChunkHydrationMetrics` | `ServerTickAdvice` + `ServerChunkLoader`/`ServerCell` advices |
| Server, per-peer wire pressure | `io.pzstorm.storm.metrics.StormConnectionMetrics` | `ServerTickAdvice` |
| Client, pipeline and stalls | `io.pzstorm.storm.client.ClientChunkStreamMetrics` | `StormLauncher`, client JVMs with `-DprometheusPort` |

Full metric-by-metric reference, including every label value: [metrics.md](metrics.md).

Client series only exist on a client launched with `-DprometheusPort=<port>` — PZ's
`StatisticManager.init()` starts its Prometheus server on that property alone, with no
`GameServer.server` check, so no new transport was needed. Anything that gets that flag onto the
client's command line makes the whole client half scrapeable.

Two of the client-side stalls also have a production-side proxy that needs no client at all:

- **Cause 5** is measured directly. `storm_chunk_stream_peer_cell_holes` reads
  `UdpConnection.getLoadedCell(index).loaded` — the server's own copy of the mirror the client's
  brake consults. The server pushes that array on change, so its copy can be staler but never
  fresher than the client's, making the count a lower bound on what is really braking the player.
- **The client's resend timer** shows up as `storm_chunk_stream_duplicate_requests_total`, which
  counts the server observing a client re-request a chunk whose first request was still unanswered
  — that is the 8-second timeout firing, visible entirely from the server.
