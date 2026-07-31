# Storm metrics → Prometheus

How Storm exposes server-side metrics to Prometheus, how to add new ones from Storm or a consumer mod, and what's currently exposed.

## Enabling metrics

Storm piggybacks on Project Zomboid's built-in Prometheus integration. PZ starts an HTTP server on the port given by `-DprometheusPort=<port>` (via `zombie.network.statistics.StatisticManager.init()`) and exposes `/metrics`, `/-/healthy`, and `/`. Storm and consumer mods register their instruments into the same registry (`io.prometheus.metrics.model.registry.PrometheusRegistry.defaultRegistry`), so everything is scraped from the same endpoint alongside PZ's built-ins (`pz_info`, `packet_*`, `player_*`, `jvm_*`).

### Gradle task

`./gradlew runProjectZomboidServer` forwards `-DprometheusPort=<port>` to the JVM when the `prometheusPort` key is set in `local.properties`:

```
prometheusPort=9090
```

Absent = metrics disabled (collectors still register safely; nothing is exported because no HTTP server is running).

### Direct exe launch

Append `-DprometheusPort=<port>` to the command line, e.g.:

```
.\ProjectZomboid64.exe -DprometheusPort=9090 -DstormType=local ...
```

### Verifying

```
curl http://<host>:<port>/metrics | grep -E '^(pz_|storm_)'
```

You should see PZ's built-ins (`pz_info`, `packet_*`, `player_*`) plus Storm's series (`pz_<area>_*`, `storm_<area>_*`).

## Architecture

| Piece | Where |
|-------|-------|
| HTTP server | Started by PZ in `StatisticManager.init()`. Storm does **not** run its own. |
| Registry | `PrometheusRegistry.defaultRegistry` (singleton). Shared by PZ, Storm, and all mods. |
| Storm helper | `io.pzstorm.storm.metrics.StormPrometheus.registry()` returns the shared registry. Use this for all `register(...)` calls. |
| Client library | `io.prometheus.metrics.core.metrics.*` (v1.x `client_java` API, NOT the legacy `simpleclient`). Already on the classpath via `projectzomboid.jar`. |
| Scope | PZ's `prometheusPort` check fires before the server-only gate, so the HTTP server starts on either client or server JVM. Storm's instrumentation is primarily server-side because most Storm patches gate on `GameServer.server`. |

Storm instruments are declared as `private static final` fields and register at class-load time. Class loading is triggered by the corresponding advice firing — i.e. metric classes don't run until their patched target class is loaded. Registration order doesn't matter; the registry is just a static collection that's read on scrape.

## Adding metrics

### Pattern

Declare instruments as `private static final` fields and register them via `StormPrometheus.registry()`. Methods called from advice should preserve their signatures so byte-buddy advice doesn't need to be touched.

```java
package io.pzstorm.storm.metrics; // or your mod's package

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;
import io.pzstorm.storm.metrics.StormPrometheus;

public final class MyModMetrics {

    private static final Counter ZOMBIES_KILLED =
            Counter.builder()
                    .name("mymod_zombies_killed_total")
                    .help("Zombies killed by players.")
                    .labelNames("weapon_type")
                    .register(StormPrometheus.registry());

    private static final Histogram ACTION_DURATION =
            Histogram.builder()
                    .name("mymod_action_duration_seconds")
                    .help("Action handler latency.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private MyModMetrics() {}

    public static void recordKill(String weapon) {
        ZOMBIES_KILLED.labelValues(weapon).inc();
    }

    public static void recordActionNanos(long nanos) {
        ACTION_DURATION.observe(nanos / 1e9);
    }
}
```

### Instrument types

| Type | Use for | Notes |
|------|---------|-------|
| `Counter` | Monotonically-increasing counts (calls, ticks, events) | `_total` suffix in name. Use `labelNames(...)` + `labelValues(...)` for breakdowns. |
| `Histogram` (native) | Per-call latency or any distribution | `.nativeOnly()` — no bucket choice. Requires Prometheus server with native histograms enabled (see below). |
| `Histogram` (classic buckets) | Distribution when native is unavailable | `.classicUpperBounds(...)` with explicit `double[]` boundaries. Bucket choice matters; pick boundaries that bracket the expected range. |
| `Gauge` | Current value that goes up and down | E.g. number of connected players. Use `set(double)`. |
| `GaugeWithCallback` | Current value pulled from somewhere at scrape time | Callback fires on each scrape. Avoid expensive work. |
| `CounterWithCallback` | Cumulative count pulled at scrape time | Callback fires on each scrape. Returns cumulative value; PromQL `rate()` derives per-second downstream. Good fit for OS / JVM stats that already accumulate. |

### Naming conventions

| Rule | Reason |
|------|--------|
| snake_case | Prometheus convention. |
| `pz_*` prefix for metrics that time PZ game code | Distinguishes from Storm-internal metrics. Matches PZ's own `pz_info`. |
| `storm_*` prefix for metrics that measure Storm framework internals | E.g. Storm's own LOS cache. |
| `<modid>_*` prefix for consumer-mod metrics | Avoid collisions across mods. |
| `_total` suffix on counters | Required by Prometheus exposition format. |
| Base units in names — `_seconds`, `_bytes` | NOT `_microseconds`, `_kilobytes`. Convert at observation time: `nanos / 1e9`. |
| Avoid high-cardinality labels (user IDs, free-form text) | Cardinality blowup degrades the scraper and Grafana. Cap at single-digit cardinality where possible. |

### Native histograms

The bundled `client_java` supports native histograms — auto-scaling exponential buckets, no fixed-bucket choice. This is the recommended type for latency / distribution metrics in Storm.

Requirements:
- Prometheus server with the `native-histograms` feature enabled (2.x: `--enable-feature=native-histograms`; 3.x: GA).
- Scrape job configured with `native_histogram_bucket_limit` or it rejects them. Check `scrape_configs:` in `prometheus.yml`.

If you can't change the scrape config, use `.classicUpperBounds(...)` with explicit buckets tuned to the expected range. Reach for native histograms when you control the scraper.

## Current metric reference

All metrics below are exposed at `/metrics` when `-DprometheusPort` is set on a server JVM. Each Storm-instrumented metric is registered by the same-name class in `src/main/java/io/pzstorm/storm/metrics/`.

### Standard composite (Histogram + Counter, 21 classes)

Each measures the duration of one advice + counts how many scheduler ticks elapsed. Same pattern across all of them: one native `Histogram` for call latency + one `Counter` for ticks.

| Histogram | Ticks counter | Triggered by |
|-----------|---------------|--------------|
| `pz_animal_sync_call_duration_seconds` | `pz_animal_sync_ticks_total` | `AnimalSyncManagerUpdateAdvice` |
| `pz_animal_update_call_duration_seconds` | `pz_animal_update_ticks_total` | `IsoAnimalUpdateTimingAdvice` |
| `pz_animal_update_los_call_duration_seconds` | `pz_animal_update_los_ticks_total` | `IsoAnimalUpdateLOSAdvice` |
| `pz_base_vehicle_update_call_duration_seconds` | `pz_base_vehicle_update_ticks_total` | `BaseVehicleUpdateAdvice` |
| `pz_chunk_load_call_duration_seconds` | `pz_chunk_load_ticks_total` | `IsoChunkLoadAdvice` |
| `pz_chunk_remove_call_duration_seconds` | `pz_chunk_remove_ticks_total` | `IsoChunkRemoveFromWorldAdvice` |
| `pz_chunk_save_call_duration_seconds` | `pz_chunk_save_ticks_total` | `IsoChunkSaveAdvice` |
| `pz_entity_manager_update_call_duration_seconds` | `pz_entity_manager_update_ticks_total` | `GameEntityManagerUpdateAdvice` |
| `pz_lua_mainloop_call_duration_seconds` | `pz_lua_mainloop_ticks_total` | `LuaMainloopAdvice` |
| `pz_netdata_call_duration_seconds` | `pz_netdata_ticks_total` | `GameServerNetDataAdvice` |
| `pz_object_remove_from_world_call_duration_seconds` | `pz_object_remove_from_world_ticks_total` | `IsoObjectRemoveFromWorldAdvice` |
| `pz_player_update_los_call_duration_seconds` | `pz_player_update_los_ticks_total` | `IsoPlayerUpdateLOSAdvice` |
| `pz_remote_player_update_call_duration_seconds` | `pz_remote_player_update_ticks_total` | `IsoPlayerUpdateRemoteAdvice` |
| `pz_server_cell_unload_call_duration_seconds` | `pz_server_cell_unload_ticks_total` | `ServerCellUnloadAdvice` |
| `pz_server_los_update_call_duration_seconds` | `pz_server_los_update_ticks_total` | `ServerLOSUpdateAdvice` |
| `pz_server_map_post_update_call_duration_seconds` | `pz_server_map_post_update_ticks_total` | `ServerMapPostUpdateAdvice` |
| `pz_using_player_update_call_duration_seconds` | `pz_using_player_update_ticks_total` | `UsingPlayerUpdateAdvice` |
| `pz_vehicle_send_call_duration_seconds` | `pz_vehicle_send_ticks_total` | `VehicleManagerSendVehiclesAdvice` |
| `pz_vehicle_server_update_call_duration_seconds` | `pz_vehicle_server_update_ticks_total` | `VehicleManagerServerUpdateAdvice` |
| `pz_zombie_manager_auth_call_duration_seconds` | `pz_zombie_manager_auth_ticks_total` | `NetworkZombieManagerAuthAdvice` |
| `pz_zombie_spot_player_call_duration_seconds` | `pz_zombie_spot_player_ticks_total` | `TestZombieSpotPlayerAdvice` |

`*_ticks_total` is incremented by `MovingObjectUpdateSchedulerStartFrameAdvice` once per scheduler frame.

Useful PromQL:

```promql
# average call duration (seconds)
rate(pz_chunk_load_call_duration_seconds_sum[1m])
  / rate(pz_chunk_load_call_duration_seconds_count[1m])

# p99 call duration
histogram_quantile(0.99, rate(pz_chunk_load_call_duration_seconds[1m]))

# time-per-tick spent in this advice
rate(pz_chunk_load_call_duration_seconds_sum[1m])
  / rate(pz_chunk_load_ticks_total[1m])

# calls per tick
rate(pz_chunk_load_call_duration_seconds_count[1m])
  / rate(pz_chunk_load_ticks_total[1m])
```

### Comparative timing (CellObjectAdd / CellObjectRemove)

Both classes patch `IsoCell` add/remove paths and time a "fast" path (every call) alongside a "vanilla simulated" path (sampled 1-in-1024 via `VANILLA_SAMPLE_MASK = 1023`). The "speedup ratio" comparison lives in PromQL (see below).

| Name | Type | What |
|------|------|------|
| `pz_cell_object_add_fast_duration_seconds` | Histogram (native) | Fast-path duration for IsoCell add operations |
| `pz_cell_object_add_vanilla_simulated_duration_seconds` | Histogram (native) | Simulated vanilla-path duration. Sampled 1-in-1024. |
| `pz_cell_object_add_ticks_total` | Counter | Scheduler ticks observed |
| `pz_cell_object_remove_fast_duration_seconds` | Histogram (native) | Fast-path duration for IsoCell remove operations |
| `pz_cell_object_remove_vanilla_simulated_duration_seconds` | Histogram (native) | Simulated vanilla-path duration. Sampled 1-in-1024. |
| `pz_cell_object_remove_ticks_total` | Counter | Scheduler ticks observed |

Useful PromQL:

```promql
# speedup ratio: simulated vanilla average / fast average
( rate(pz_cell_object_add_vanilla_simulated_duration_seconds_sum[1m])
  / rate(pz_cell_object_add_vanilla_simulated_duration_seconds_count[1m]) )
/
( rate(pz_cell_object_add_fast_duration_seconds_sum[1m])
  / rate(pz_cell_object_add_fast_duration_seconds_count[1m]) )
```

### BitHeader pool

Volume counters for `zombie.util.io.BitHeader` pool operations.

| Name | Type | Labels | What |
|------|------|--------|------|
| `pz_bit_header_pool_ops_total` | Counter | `size={byte,short,integer,long}`, `op={get,release}` | BitHeader pool gets and releases by size. |

### Thread allocation

Per-thread heap allocation, exposed as cumulative bytes via `CounterWithCallback`. The callback enumerates live threads on each scrape (bounded by the tracked set — does not iterate the entire thread set's allocation reads) and reads `com.sun.management.ThreadMXBean.getThreadAllocatedBytes(tid)`.

| Name | Type | Labels | What |
|------|------|--------|------|
| `pz_thread_allocated_bytes_total` | CounterWithCallback | `thread` | Cumulative bytes allocated per tracked thread. |

Label values: `main`, `SaveChunk`, `LoadChunk`, `LOS`, `RecalcAll`, `WorldReuser`, `UdpEngine`, `ServerPlayersVehicles`, `IsoRegionWorker`, `player_download` (aggregates all `PlayerDownloadServer*` threads). If a tracked thread is briefly absent, the series stays present with value `0`.

Loaded indirectly via `BitHeaderMetrics`'s static initializer (`ThreadAllocBytesMetrics.ensureStarted()`), so the callback registers the moment the first BitHeader patch fires.

### Storm internals

Metrics that measure Storm's own caches / framework code, not PZ behavior.

#### ServerLOS find-data cache

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_server_los_find_data_lookups_total` | Counter | `result={hit,miss}` | ServerLOS find-data cache lookups by result. |
| `storm_server_los_find_data_cache_size` | GaugeWithCallback | — | Current entry count in the ServerLOS find-data cache. Backed by `ServerLOSPlayerDataCache.size()`. |

#### Event dispatch

`StormEventDispatcher.dispatchEvent` fanout. The handler-duration histogram covers the full handler loop for one event (sum of all `@SubscribeEvent` handlers), not per-handler. `event` label is the event class simple name; cardinality is bounded by `LuaEventFactory`'s class list plus any mod-defined events.

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_event_dispatch_total` | Counter | `event` | Events dispatched with at least one registered handler. |
| `storm_event_handler_duration_seconds` | Histogram (native) | `event` | Wall-clock time spent dispatching one event across all its handlers. |
| `storm_event_handler_errors_total` | Counter | `event` | Handler invocations that threw `RuntimeException`. |

#### Packet dispatch

`PacketEventDispatcher` activity. `packet` label is the packet's simple class name, bounded by `SUPPORTED_PACKETS` (~120 entries). The typed-event counter exposes the effectiveness of the constructor cache + `NO_TYPED_EVENT` short-circuit.

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_packet_dispatch_total` | Counter | `packet` | Packets routed through `dispatchPacket`. |
| `storm_packet_handler_duration_seconds` | Histogram (native) | `packet` | Wall-clock time spent dispatching to all `@OnPacketReceived` handlers for one packet. |
| `storm_packet_typed_event_total` | Counter | `packet`, `result={hit,miss,none,error}` | Outcomes of typed-event construction (hit/miss/none/error). |

#### Transfer handler

`StormTransferHandler` lifecycle for Storm's UUID-based item-transfer system. `accepted` is incremented when a transfer enters `pendingTransfers`; the terminal outcomes (`done`, `rejected`, `cancelled`) are independent counters — every accepted transfer eventually adds exactly one terminal increment. The settle histogram is observed only on `done` and measures wall-clock from accept → done.

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_transfer_requests_total` | Counter | `outcome={accepted,rejected,done,cancelled}` | Transfer lifecycle events. |
| `storm_transfer_pending_size` | GaugeWithCallback | — | Current number of in-flight transfers. Backed by `StormTransferHandler.pendingSize()`. |
| `storm_transfer_settle_duration_seconds` | Histogram (native) | — | Wall-clock duration from accept to done. |

#### Per-peer network telemetry + send-buffer watchdog

`StormConnectionMetrics.recordAll()` is called every server tick from `ServerTickAdvice` (server-only). It iterates `GameServer.udpEngine.connections`, reads each connection's `ZNetStatistics`, and publishes per-peer gauges labelled by `username`. Vanilla PZ exports the same numbers but sums them across all peers (`network{parameter="bytes-in-send-buffer-high"}`); the per-peer breakdown identifies *which* peer is filling the buffer during a chunk-transfer storm or congested-link incident. When a peer disconnects between ticks, its label series are explicitly set to `0` so the drop is visible as a step-down rather than a stale flat line.

The watchdog (see `Storm.PeerSendBufferKickMb` / `Storm.PeerSendBufferKickHoldTicks` in [Server Configuration](server-configuration.md)) reads the same per-peer HIGH send-buffer value each tick and force-disconnects peers that stay above the threshold for the configured hold window. `username` is bounded by concurrent player population; `guid:<rakNetGuid>` is used as a fallback when the connection authenticated but never set a username (~the same bound).

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_peer_send_buffer_bytes` | Gauge | `username`, `priority={immediate,high,medium,low}` | Pending outbound bytes per peer in RakNet's send queue. Spikes on the `high` series identify chunk-transfer / broadcast-storm recipients. |
| `storm_peer_resend_buffer_bytes` | Gauge | `username` | Reliable bytes awaiting ACK retransmission. Sustained growth precedes a timeout-driven disconnect. |
| `storm_peer_packetloss_last_second` | Gauge | `username` | `RakNetStatistics::packetlossLastSecond` (0..1). A peer pinned high here is congesting. |
| `storm_peer_average_ping_ms` | Gauge | `username` | Running-average RTT (ms) to the peer. |
| `storm_peer_congestion_limited` | Gauge | `username` | `1` when RakNet's congestion control is currently throttling outbound BPS for this peer, else `0`. |
| `storm_peer_bps_limit_congestion` | Gauge | `username` | Current outbound BPS ceiling imposed by congestion control (bytes/second). Drops toward 0 as loss increases. |
| `storm_peer_kicked_send_buffer_total` | Counter | — | Cumulative peers force-disconnected by the watchdog. Unlabelled to keep cardinality bounded; the kicked peer's username/steamId/IP/observed-MB are logged at INFO in `storm/main.log`. |

The sandbox knobs that drive the watchdog are also exposed:

| Name | Type | What |
|------|------|------|
| `storm_peer_send_buffer_kick_mb` | Gauge | Current `Storm.PeerSendBufferKickMb` value (MB). `0` = watchdog disabled. |
| `storm_peer_send_buffer_kick_hold_ticks` | Gauge | Current `Storm.PeerSendBufferKickHoldTicks` value (server ticks). |

Useful PromQL:

```promql
# top-5 peers by HIGH send buffer
topk(5, storm_peer_send_buffer_bytes{priority="high"})

# peers actively throttled by RakNet congestion control
storm_peer_congestion_limited == 1

# auto-kicks in the last hour
increase(storm_peer_kicked_send_buffer_total[1h])
```

#### Connection lifecycle / login funnel

`StormConnectionStageMetrics.recordAll()` is called every server tick from `ServerTickAdvice` (server-only), alongside the per-peer telemetry above. Where `storm_peer_*` describes peers that are already players, this describes **every connection holding a RakNet slot**, at whatever stage of the login pipeline it occupies.

That gap is what took a production server offline: `GameServer.startServer` builds the RakNet peer with a hard-coded cap of 101 incoming connections regardless of `MaxPlayers`, and connections that never finish logging in hold their slot indefinitely. Vanilla exports spawned-player counts and per-parameter network aggregates — nothing that counts the pre-spawn population — so a peer filling with half-open connections was invisible until every new joiner silently wedged on "Getting Server Info..." (RakNet answers a full peer with `ID_NO_FREE_INCOMING_CONNECTIONS`, which the vanilla client never handles: no error, no timeout). See [`StalledConnectionReaper`](what-storm-changes.md) for the fix and `storm.raknet.connectionHeadroom` in [Server Configuration](server-configuration.md) for the cap.

Everything is sampled on the main thread and pushed into plain `Gauge`s rather than read from scrape-time callbacks. `UdpEngine.connections`, `LoginQueue`'s monitor and the RakNet peer are all main-thread state; reading them from an HTTP scrape thread inverts `ChatBase.memberLock` against `UdpConnection.bufferLock`, which has frozen this server before.

**Stages** (`io.pzstorm.storm.connection.ConnectionStage`) are mutually exclusive and evaluated in pipeline order, so the per-stage counts always sum to `storm_connection_slots_used`:

| `stage` | Meaning | Reaped? |
|---------|---------|---------|
| `handshake` | RakNet accepted the socket, no `Login` packet yet — no username. The one stage vanilla's own reap in `GameServer.main` can free. | yes |
| `google_auth` | Logged in, waiting on Google second-factor auth. | exempt until timeout |
| `google_auth_timeout` | Second factor never completed within the vanilla 60s window. Vanilla never frees these. | yes |
| `coop_approve` | Co-op slave waiting for the host to approve. | exempt |
| `queued` | Waiting in `LoginQueue` behind other joiners. | exempt |
| `loading` | The login queue's current occupant: told to proceed, not yet spawned. | exempt |
| `checksum` | Logged in, Lua/script/anim checksum not yet verified (`ChecksumState.Init`). | yes |
| `checksum_mismatch` | Failed the checksum comparison (`ChecksumState.Different`) — mod mismatch or tampered scripts. | yes |
| `awaiting_spawn` | Authenticated and past the queue, character not spawned: downloading chunks, on the character screen, or a "Click Start" camper. `setFullyConnected()` only fires in `receivePlayerConnect`. | yes |
| `fully_connected` | Character is in the world. The only stage that counts against `MaxPlayers`. | never |

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_connections` | Gauge | `stage` | Connections holding a RakNet slot per pipeline stage. Sums to `storm_connection_slots_used`. All ten series are written every tick, including empty ones, so a stage emptying reads as a step down to `0`. |
| `storm_connection_stage_age_seconds_max` | Gauge | `stage` | Age of the oldest connection currently in each stage, from the first tick Storm sampled it (a lower bound for connections predating startup). `0` when the stage is empty. |
| `storm_connection_slots_used` | Gauge | — | Slots in use (`GameServer.udpEngine.connections` size) — every connection at any stage, not just players. |
| `storm_connection_slots_max` | Gauge | — | The cap actually in force (`UdpEngine.getMaxConnections()`). Published from `GameServerConnectionCapPatch` at boot, then re-read off the live engine each tick. |
| `storm_connection_raknet_peers` | Gauge | — | RakNet's own count (`RakNetPeerInterface.GetConnectionsNumber()`). Exceeds `storm_connection_slots_used` while RakNet holds peers `UdpEngine` has not wrapped in a `UdpConnection` yet — slot occupancy invisible to every other series here. Stays at `0` with a WARN in `storm/main.log` if the native is unavailable. |
| `storm_connection_cap_vanilla` | Gauge | — | The cap vanilla hard-codes (101). Constant baseline, so a dashboard can show Storm's headroom as `storm_connection_slots_max - this`. |
| `storm_connection_cap_fallback` | Gauge | — | `1` when RakNet refused to start with the raised cap and the peer was built with the vanilla cap instead — the headroom is *not* in place. |
| `storm_connection_reap_age_seconds_max` | Gauge | — | Largest time-on-the-reap-clock across all non-fully-connected connections. Distinct from `stage_age`: the reaper restamps this clock while a connection is exempt, so this is the number compared against the timeout. |
| `storm_connection_reap_timeout_seconds` | Gauge | — | Wall-clock budget to finish logging in and spawn. Default `420` (7 min), `-Dstorm.reapStalledConnectionMs`. |
| `storm_connection_reap_sweep_interval_seconds` | Gauge | — | Sweep period — also the granularity of `reap_age` and the worst-case overshoot past the timeout. Default `30`, `-Dstorm.reapSweepIntervalMs`. |
| `storm_connection_reaped_total` | Counter | `stage` | Slots freed by the reaper, attributed to the stage the connection was stuck in. Every reap is also logged at WARN with the connection id. |
| `storm_connection_login_duration_seconds` | Histogram (native) | — | First sample → character spawned. Observed once per connection, and only for connections Storm saw in a pre-spawn stage first (already-spawned-at-first-sample is skipped rather than reported as instant). |
| `storm_steam_advertised_players` | Gauge | — | Steam user-list size maintained by `SteamPlayerListReconciler` — the player count the server browser / A2S / BattleMetrics see. Spawned players first, then pre-spawn pipeline connections, then login-queue waiters (post-login connections with no player id yet, advertised under synthetic ids), clamped at `MaxPlayers`; equals `min(storm_connections{stage="fully_connected"} + pre-spawn stages with a player id + post-login connections awaiting one, MaxPlayers)` while active. `0` when the reconciler is disabled (`-Dstorm.steam.advertisePipelinePlayers=false`), broken, or the server is not in Steam mode — vanilla then advertises spawned players only. |

Separately, `ConnectionManagerLogPatch` turns PZ's `connections` log into counters. `ConnectionManager.log` is the single choke point every connection-lifecycle event already passes through — RakNet accepts and drops, each handshake packet in both directions, the spawn itself — so one patch covers ~27 event types.

**The labels invert PZ's own field names.** PZ writes `event="RakNet" message="new-incoming-connection"`, where its `event` field is really the channel. So PZ's first argument becomes `source` and its second becomes `event`, which is the order that makes `sum by (event)` useful.

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_connection_events_total` | Counter | `source`, `event` | One per `ConnectionManager.log` call on the server. `source` ∈ `{RakNet, receive-packet, send-packet, fully-connected, Storm}`; `event` is PZ's message (`new-incoming-connection`, `login`, `login-queue-request`, `player-connect`, `invalid-password`, `connection-banned`, `connection-lost`, `stalled-connection-reap`, …). Cardinality is capped at 64 pairs; overflow lands in `source="other"`, and PZ's empty message on `fully-connected` becomes `event="none"`. |

Note `event="no-free-incoming-connections"` never appears on a server: RakNet's `ID_NO_FREE_INCOMING_CONNECTIONS` is only ever *received*, and only by clients. Exhaustion has to be inferred from `slots_used` / `slots_max` / `raknet_peers`, which is exactly why those three exist.

Useful PromQL:

```promql
# the funnel, stacked — pre-spawn stages vs players
sum by (stage) (storm_connections)

# how full is the peer? alert well before 1.0 — a full peer is a silent outage
storm_connection_slots_used / storm_connection_slots_max > 0.8

# slots RakNet holds that the Java side cannot see
storm_connection_raknet_peers - storm_connection_slots_used > 0

# headroom Storm added over vanilla; 0 means the raise did not take
storm_connection_slots_max - storm_connection_cap_vanilla

# connections stuck pre-spawn (the leak shape) — everything but the terminal stage
sum(storm_connections) - storm_connections{stage="fully_connected"}

# closest connection to being reaped, as a fraction of its budget
storm_connection_reap_age_seconds_max / storm_connection_reap_timeout_seconds

# where connections are dying
sum by (stage) (rate(storm_connection_reaped_total[1h]))

# accepts that never became logins (funnel drop-off, per second)
  rate(storm_connection_events_total{source="RakNet",event="new-incoming-connection"}[15m])
- rate(storm_connection_events_total{source="receive-packet",event="login"}[15m])

# logins that never became spawns
  rate(storm_connection_events_total{source="receive-packet",event="login"}[15m])
- rate(storm_connection_events_total{source="receive-packet",event="player-connect"}[15m])

# rejections by reason
sum by (event) (rate(storm_connection_events_total{source="RakNet"}[15m]))

# median login time
histogram_quantile(0.5, rate(storm_connection_login_duration_seconds[15m]))
```

#### World-wide zombie ceiling

`StormZombieTotalCap.onServerTick()` runs from `ServerTickAdvice` (server-only). When the live zombie count exceeds `Storm.MaxTotalZombies` it sweeps once per second and deletes up to 200 surplus zombies, restricted to zombies nobody can see. Vanilla has no global cap — see [Server Configuration](server-configuration.md#there-is-no-vanilla-cap-on-the-world-wide-zombie-total) for why the world total matters and which levers to reach for first.

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_max_total_zombies` | Gauge | — | Configured ceiling. `0` = disabled. |
| `storm_zombies_total_cap_culled_total` | Counter | — | Cumulative zombies deleted by the cap. |

The counter is only meaningful against the ceiling and the live total (`storm_zombie_id_pool_size`, backed by `ServerMap.instance.zombieMap`). A burst after a population spike is the cap working; a rate that never returns to zero means the population settings want more zombies than the cap permits and the two are fighting — lower `ZombieConfig.PopulationMultiplier` rather than raising the cap.

```promql
# is the cap actively fighting the population manager?
rate(storm_zombies_total_cap_culled_total[15m]) > 0

# headroom: live zombies as a fraction of the ceiling (ignores the disabled case)
storm_zombie_id_pool_size / (storm_max_total_zombies > 0)
```

#### HTTP endpoint

`HttpEndpointDispatcher` activity. `path` is the matched route — requests to unregistered paths are bucketed under `path="unknown"` to keep cardinality bounded. `status` is the HTTP status code as a string (e.g. `"200"`, `"404"`, `"500"`).

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_http_requests_total` | Counter | `method`, `path`, `status` | One per HTTP request handled. |
| `storm_http_request_duration_seconds` | Histogram (native) | `method`, `path` | Wall-clock duration of one HTTP request, from dispatch entry to exchange close. |

## Gotchas

- **Callback metrics fire on every scrape.** Keep callbacks cheap. `ThreadAllocBytesMetrics` skips threads that aren't tracked before doing the per-thread bean read, specifically to keep scrape latency bounded.
- **PZ's `StatisticManager.init()` doesn't run unless `prometheusPort` is set.** If the property is absent, Storm collectors register fine but nothing is exposed — there's no HTTP server.
- **Native histograms need scrape-side configuration.** If `pz_*_call_duration_seconds` shows up only as `_count` and `_sum` in your scrape, the scraper isn't accepting native histograms — flip the Prometheus feature flag and add `native_histogram_bucket_limit` to the scrape job.
- **Class loading is lazy.** A metric class only registers its instruments when its enclosing advice's target class is loaded. If a metric is missing from `/metrics`, check that the advice has actually fired at least once — the `_count` will be `0` after first fire even if no observations exist.
