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
