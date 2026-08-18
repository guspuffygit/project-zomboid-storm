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

All metrics below are exposed at `/metrics` when `-DprometheusPort` is set on a server JVM — except the [client chunk streaming](#client-chunk-streaming-clientchunkstreammetrics) family, which comes from a client JVM launched with the same property. Each Storm-instrumented metric is registered by the same-name class in `src/main/java/io/pzstorm/storm/metrics/` (or `.../storm/client/` for the client-side family).

### Advice latency histograms (`pz_*_call_duration_seconds`)

Each of these is a single native `Histogram` recording the wall-clock duration of one advised
call. There is no companion tick counter — use `_count` for the call rate and `_sum` for total
time spent, both of which every native histogram exposes.

The family splits into two tables. First, the advices attached to the per-frame scheduler — the
work that runs on every server tick, and therefore the series whose `_sum` rates add up to the
main thread's budget.

| Name | Triggered by |
|------|--------------|
| `pz_animal_sync_call_duration_seconds` | `AnimalSyncManagerUpdateAdvice` |
| `pz_animal_update_call_duration_seconds` | `IsoAnimalUpdateTimingAdvice` |
| `pz_animal_update_los_call_duration_seconds` | `IsoAnimalUpdateLOSAdvice` |
| `pz_base_vehicle_update_call_duration_seconds` | `BaseVehicleUpdateAdvice` |
| `pz_chunk_load_call_duration_seconds` | `IsoChunkLoadAdvice` |
| `pz_chunk_remove_call_duration_seconds` | `IsoChunkRemoveFromWorldAdvice` |
| `pz_chunk_save_call_duration_seconds` | `IsoChunkSaveAdvice` |
| `pz_entity_manager_update_call_duration_seconds` | `GameEntityManagerUpdateAdvice` |
| `pz_lua_mainloop_call_duration_seconds` | `LuaMainloopAdvice` |
| `pz_netdata_call_duration_seconds` | `GameServerNetDataAdvice` |
| `pz_object_remove_from_world_call_duration_seconds` | `IsoObjectRemoveFromWorldAdvice` |
| `pz_player_update_los_call_duration_seconds` | `IsoPlayerUpdateLOSAdvice` |
| `pz_remote_player_update_call_duration_seconds` | `IsoPlayerUpdateRemoteAdvice` |
| `pz_server_cell_unload_call_duration_seconds` | `ServerCellUnloadAdvice` |
| `pz_server_los_update_call_duration_seconds` | `ServerLOSUpdateAdvice` |
| `pz_server_map_post_update_call_duration_seconds` | `ServerMapPostUpdateAdvice` |
| `pz_using_player_update_call_duration_seconds` | `UsingPlayerUpdateAdvice` |
| `pz_vehicle_send_call_duration_seconds` | `VehicleManagerSendVehiclesAdvice` |
| `pz_vehicle_server_update_call_duration_seconds` | `VehicleManagerServerUpdateAdvice` |
| `pz_zombie_manager_auth_call_duration_seconds` | `NetworkZombieManagerAuthAdvice` |

Second, the rest of the family: server subsystems that do not run every frame — periodic
housekeeping, the login funnel, per-disconnect cleanup, and the Steam / RCON / public-listing side
channels. Same instrument, same naming, same one-class-per-metric layout under
`src/main/java/io/pzstorm/storm/metrics/`. These are low-volume by construction, so `_count` is
usually the more interesting half: a periodic step that quietly stops running shows up as a flat
`_count` here long before it shows up as a symptom anywhere else. The `remove*` four fire once per
player disconnect and are the ones to check when leaving a full server is slow for everyone still
on it.

| Name | Triggered by |
|------|--------------|
| `pz_animal_instance_manager_remove_animals_call_duration_seconds` | `RemoveAnimalsAdvice` |
| `pz_client_server_map_character_in_call_duration_seconds` | `ClientServerMapCharacterInAdvice` |
| `pz_coop_slave_update_call_duration_seconds` | `CoopSlaveUpdateAdvice` |
| `pz_file_system_update_async_transactions_call_duration_seconds` | `FileSystemUpdateAsyncTransactionsAdvice` |
| `pz_important_area_manager_process_call_duration_seconds` | `ImportantAreaManagerProcessAdvice` |
| `pz_ingame_state_update_call_duration_seconds` | `IngameStateUpdateAdvice` |
| `pz_iso_dead_body_remove_dead_bodies_call_duration_seconds` | `RemoveDeadBodiesAdvice` |
| `pz_login_queue_update_call_duration_seconds` | `LoginQueueUpdateAdvice` |
| `pz_map_collision_data_update_game_state_call_duration_seconds` | `MapCollisionDataUpdateGameStateAdvice` |
| `pz_network_player_manager_update_call_duration_seconds` | `NetworkPlayerManagerUpdateAdvice` |
| `pz_network_zombie_manager_remove_zombies_call_duration_seconds` | `RemoveZombiesAdvice` |
| `pz_object_id_manager_check_save_data_call_duration_seconds` | `ObjectIDManagerCheckSaveDataAdvice` |
| `pz_packet_validator_update_call_duration_seconds` | `PacketValidatorUpdateAdvice` |
| `pz_player_download_server_update_call_duration_seconds` | `PlayerDownloadServerUpdateAdvice` |
| `pz_public_server_util_update_call_duration_seconds` | `PublicServerUtilUpdateAdvice` |
| `pz_public_server_util_update_player_count_call_duration_seconds` | `PublicServerUtilUpdatePlayerCountAdvice` |
| `pz_rcon_server_update_call_duration_seconds` | `RCONServerUpdateAdvice` |
| `pz_safe_house_update_call_duration_seconds` | `SafeHouseUpdateAdvice` |
| `pz_send_world_map_player_position_call_duration_seconds` | `SendWorldMapPlayerPositionAdvice` |
| `pz_server_cell_load2_call_duration_seconds` | `ServerCellLoad2Advice` |
| `pz_server_gui_update_call_duration_seconds` | `ServerGUIUpdateAdvice` |
| `pz_server_map_character_in_call_duration_seconds` | `ServerMapCharacterInAdvice` |
| `pz_server_map_pre_update_call_duration_seconds` | `ServerMapPreUpdateAdvice` |
| `pz_statistic_manager_update_call_duration_seconds` | `StatisticManagerUpdateAdvice` |
| `pz_steam_utils_run_loop_call_duration_seconds` | `SteamUtilsRunLoopAdvice` |
| `pz_trading_manager_update_call_duration_seconds` | `TradingManagerUpdateAdvice` |
| `pz_udp_connection_calc_count_players_in_relevant_position_call_duration_seconds` | `CalcCountPlayersInRelevantPositionAdvice` |
| `pz_vehicle_manager_remove_vehicles_call_duration_seconds` | `RemoveVehiclesAdvice` |
| `pz_war_manager_update_call_duration_seconds` | `WarManagerUpdateAdvice` |
| `pz_world_map_visited_server_update_call_duration_seconds` | `WorldMapVisitedServerUpdateAdvice` |
| `pz_zip_backup_on_period_call_duration_seconds` | `ZipBackupOnPeriodAdvice` |

Two members of the family are documented with the subsystem they belong to rather than here:
`pz_chunk_save_loaded_call_duration_seconds` and `pz_player_download_dedupe_call_duration_seconds`
both live in the [chunk streaming](#chunk-streaming-chunkstreammetrics) table.

Useful PromQL:

```promql
# average call duration (seconds)
rate(pz_chunk_load_call_duration_seconds_sum[1m])
  / rate(pz_chunk_load_call_duration_seconds_count[1m])

# p99 call duration
histogram_quantile(0.99, rate(pz_chunk_load_call_duration_seconds[1m]))

# calls per second
rate(pz_chunk_load_call_duration_seconds_count[1m])

# share of wall-clock spent in this advice
rate(pz_chunk_load_call_duration_seconds_sum[1m])
```

### Server tick rate (ServerTickMetrics)

The first two series to look at when someone says the server feels laggy. `ServerTickAdvice` sits
on `StatisticManager.update(long)` — registered server-only, because `GameClient` calls the same
method — and records PZ's own `dif`, the wall-clock milliseconds between consecutive
`GameServer.main` cycles.

That is *cycle* time, not work time: it includes whatever the update limiter slept, so a healthy
server sits flat at 0.1 s and any sustained value above that is the loop failing to finish inside
its own budget. Everything in the per-frame advice table above is a component of this number.

This is the honest counterpart to PZ's `performance{parameter="fps"}`, which is the same `dif`
mislabelled as an FPS, exported only once every `multiplayerStatisticsPeriod` seconds and smoothed
through a decay that never converges. These record every tick, unthrottled.

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_server_tick_total` | Counter | — | Server update ticks completed. `rate()` is TPS; the target is 10. A rate below 10 while the duration histogram sits above 0.1 s is a saturated main thread. A rate below 10 while the histogram stays *flat* means ticks are being skipped rather than stretched — look at GC and at anything blocking outside the measured cycle. |
| `storm_server_tick_duration_seconds` | Histogram (native) | — | Cycle time of one tick in seconds, converted from PZ's millisecond `dif`. ~0.1 s when healthy. The median tracks steady-state load; the p99 tail is where GC pauses, cell unloads, and chunk-save spikes land. It is `1/TPS` by construction, so this and the counter are two views of one number — this is the view that shows the shape. |

Useful PromQL:

```promql
# ticks per second (TPS); 10 is the target
rate(storm_server_tick_total[1m])

# typical and tail cycle time
histogram_quantile(0.50, rate(storm_server_tick_duration_seconds[1m]))
histogram_quantile(0.99, rate(storm_server_tick_duration_seconds[1m]))

# how far below the 10 TPS target the server is running, as a fraction
1 - rate(storm_server_tick_total[1m]) / 10
```

### Player-LOS fast path (StormPlayerLos)

Tallies for the distance-culled, server-stripped `IsoPlayer.updateLOS()` replacement
(`Storm.PlayerLosFastPath`). Backed by plain non-atomic `long`s flushed once per call and read at
scrape time via `CounterWithCallback` — this path runs per player × per moving object per tick,
where an eager atomic `Counter.inc()` once cost ~8% of the main thread. The existing
`pz_player_update_los_call_duration_seconds` histogram keeps timing the full call on both paths
(its stopwatch advice wraps the fast-path skip), so before/after comparison stays valid. Note
`pz_server_los_could_see_calls_total` drops when the fast path is active: it counts real
`ServerLOS.isCouldSee` invocations, and the fast path replaces most of them with direct
visibility-cube reads.

| Name | Type | Labels | What |
|------|------|--------|------|
| `pz_player_update_los_calls_total` | CounterWithCallback | `path={optimized,vanilla}` | `updateLOS()` invocations by executed path (`vanilla` = kill switch off, failure latch, or no `PlayerData` cached yet). |
| `pz_player_update_los_objects_total` | CounterWithCallback | `outcome={culled,processed}` | Moving objects rejected by the visibility-cube distance check vs walked through the stripped loop body. |

### Parallel ServerLOS engine (StormServerLosMetrics)

Timings for `StormServerLos`, the drop-in replacement for `ServerLOS$LOSThread.runInner` that fans
per-player line-of-sight scans across up to 16 scratch slots. Registered and fed on the dedicated
server only.

These record on **every** configuration, including the default `Storm.ServerLosThreads = 1`, where
the Storm engine still owns the tick but runs the whole batch single-threaded on slot 0 with the
helper pool and the onSee lock inert, producing a `visible` grid byte-identical to vanilla. That is
deliberate: the single-threaded run is a like-for-like baseline you can diff against a parallel run
without the instrumentation itself changing.

LOS runs on its own thread, so a slow LOS tick does not appear in
`storm_server_tick_duration_seconds` — it appears as players noticing zombies late. Read
`storm_serverlos_tick_seconds` against `storm_serverlos_batch_players` before concluding anything:
a tick that got slower because there are more players in it is not the same problem as one that got
slower per player.

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_serverlos_tick_seconds` | Histogram (native) | — | Wall time of one whole LOS tick: batch collection, slice dispatch, the inline slot-0 slice, and the join. Every worker is joined before the tick returns, so this tracks the *slowest* slice rather than the average — one player standing in a pathological interior sets the tick cost for everybody. |
| `storm_serverlos_calclos_seconds` | Histogram (native) | `slot` | One player's LOS grid scan, labelled by the scratch slot that ran it. `slot` is the decimal worker index, `0` through `15` (`Storm.ServerLosThreads` clamps to 16). Slot `0` always runs inline on the LOS thread and is the only slot present at the default `threads = 1`; higher slots come and go because the dispatcher cuts `min(threads, players)` contiguous slices. Consistent skew between slots means the slicing is unbalanced, not that one slot is slow. |
| `storm_serverlos_batch_players` | Histogram (native) | — | Players processed in one LOS tick. This is a **count**, not seconds, despite sitting between two duration histograms. Only players whose status is `WaitingInLOS` when the batch is collected are claimed, so it runs below the connected-player count and falls to 0 while the map is loading. |
| `storm_serverlos_onsee_locked_total` | Counter | — | `IsoRoom.onSee` invocations that had to serialize on the parallel-LOS lock. Exactly 0 at `threads = 1`, because the lock is only armed while helper workers are in flight — so every increment is contention that parallelism introduced. This is the cost side of raising `Storm.ServerLosThreads`: if it climbs faster than scan throughput improves, room discovery has become the limiter and more slots will not help. |
| `storm_serverlos_threads` | GaugeWithCallback | — | Worker slots currently configured, read live from `StormServerLosConfig.threads()` at scrape time. Same number as the pushed `storm_server_los_threads` sandbox gauge below; the two disagreeing means a setter path failed to publish, and this callback is the one to trust. |

Useful PromQL:

```promql
# per-player scan cost by worker slot — skew means the slicing is unbalanced
histogram_quantile(0.99, sum by (slot) (rate(storm_serverlos_calclos_seconds[1m])))

# LOS ticks per second, and mean players per tick
rate(storm_serverlos_tick_seconds_count[1m])
rate(storm_serverlos_batch_players_sum[1m])
  / rate(storm_serverlos_batch_players_count[1m])

# contention that parallelism introduced; 0 at Storm.ServerLosThreads = 1
rate(storm_serverlos_onsee_locked_total[1m])
```

### UsingPlayer registry sweep (UsingPlayerRegistry)

Tallies for the registry-backed replacement of `UsingPlayerUpdateSystem.update()`
(`Storm.UsingPlayerSweepFastPath`). Vanilla null-checks every iso-bucket entity per tick; the
optimized sweep iterates only the entities whose `usingPlayer` is currently non-null, tracked by
advice on `GameEntity.setUsingPlayer` and the `receiveUpdateUsingPlayer` packet handler. Sweep
counters are plain non-atomic `long`s read at scrape time via `CounterWithCallback` (one increment
per tick, main-thread writers only). The existing `pz_using_player_update_call_duration_seconds`
histogram keeps timing the full call on both paths (its stopwatch advice wraps the sweep skip), so
before/after comparison stays valid. The applied kill-switch value is exposed as
`storm_using_player_sweep_fast_path` alongside the other sandbox gauges.

| Name | Type | Labels | What |
|------|------|--------|------|
| `pz_using_player_update_sweeps_total` | CounterWithCallback | `path={optimized,vanilla}` | `update()` invocations by executed path (`vanilla` = kill switch off or failure latch). |
| `storm_using_player_registry_size` | GaugeWithCallback | — | Entities currently registered with a non-null `usingPlayer` (≈ players with a crafting/entity UI open). |

### Fluid-container update fast path (StormFluidContainerUpdate)

Tallies for the hoisted, reordered replacement of `FluidContainerUpdateSystem.updateSimulation()`
(`Storm.FluidContainerUpdateFastPath`). Vanilla re-reads climate/day-length state per entity and
scans each container's fluid list for the `"Petrol"` compare before the cheap rain guards; the
optimized pass hoists the invariants, checks the branches' shared cheap prefix first, and compares
petrol by `FluidType` identity. Tallies are plain non-atomic `long`s flushed once per pass and read
at scrape time via `CounterWithCallback` (main-thread writers only). The applied kill-switch value
is exposed as `storm_fluid_container_update_fast_path` alongside the other sandbox gauges.

| Name | Type | Labels | What |
|------|------|--------|------|
| `pz_fluid_container_update_passes_total` | CounterWithCallback | `path={optimized,vanilla}` | `updateSimulation()` invocations by executed path (`vanilla` = kill switch off or failure latch). |
| `pz_fluid_container_update_entities_total` | CounterWithCallback | `outcome={short_circuited,worked}` | Entities examined by the optimized pass: exited on the cheap guards before any fluid-list work vs ran the petrol comparison and/or rain-fill branch. |

### ECS class cache (EcsClassCache)

Miss tally for the `ClassValue` memoization of the static `ECSComponent.getECSClass(Class)`
superclass walk (`Storm.EcsClassCache`). The memoized path deliberately records **nothing** — it
runs millions of times per second, so the only instrumentation is a non-atomic miss counter
incremented inside `ClassValue.computeValue`, which executes once per distinct class. A flat
`storm_ecs_class_cache_misses_total` with the cache enabled means every lookup is a hit. The
applied kill-switch value is exposed as `storm_ecs_class_cache` alongside the other sandbox gauges.

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_ecs_class_cache_misses_total` | CounterWithCallback | — | Distinct classes memoized (`computeValue` executions); hits are intentionally uncounted. |

### Cell-unload budget (StormCellUnloadBudget)

Tallies for the budgeted replacement of the `ServerMap.postupdate()` cell-unload loop
(`Storm.CellUnloadBudgetPerTick`). Vanilla unloads every stale cell in a single tick; the budgeted
pass unloads at most N per tick and leaves the rest in `loadedCells` for later ticks. Tallies are
plain non-atomic `long`s flushed once per tick and read at scrape time via `CounterWithCallback`
(main-thread writers only). The existing `pz_server_map_post_update_call_duration_seconds`
histogram keeps timing the full call on both paths (its stopwatch advice wraps the budget skip), so
before/after spike comparison stays valid; the per-unload internals
(`ServerCell.Unload` / `IsoChunk.removeFromWorld` timing advices) also keep recording because the
budgeted pass calls the same patched methods. The applied budget is exposed as
`storm_cell_unload_budget_per_tick` alongside the other sandbox gauges.

| Name | Type | Labels | What |
|------|------|--------|------|
| `pz_server_cell_unloads_total` | CounterWithCallback | `outcome={unloaded,deferred}` | Stale cells destructively unloaded within the budget vs left loaded for a later tick because the budget was exhausted. Only counts while the budgeted pass is active. |

### Cell warming (StormCellWarmingMetrics)

Cell warming is opt-in and **off by default** — every series here reads a flat `0` unless the
server JVM was started with `-Dstorm.cells.keepWarm=true`. With it on, `StormCellWarmer` body-
replaces `ServerMap.postupdate`: a cell that leaves player influence has its chunks detached from
collision, pathfinding and both population managers and its animals and dead bodies drained into a
side stash, but the `ServerCell` itself stays in `cellMap` and `loadedCells` with `isLoaded = true`.
Walking back in re-attaches it, which is the whole point: no disk read, no binary parse, no
`RecalcAll2`.

Two things bound it. `-Dstorm.cells.maxWarm` (default 64) caps the warm set, because a warm cell
keeps its full chunk and square state resident; cells over the cap are evicted through the ordinary
destructive unload, oldest-warmed first. And an eligibility predicate refuses to warm anything
during a soft reset or a queued save/quit, falling through to vanilla unload instead.

Warming also takes ownership of `postupdate` away from the cell-unload budget above — the two body
replacements are mutually exclusive and the warm advice is registered outermost — so
`pz_server_cell_unloads_total` stops moving entirely while warming is on. The
`pz_server_map_post_update_call_duration_seconds` timing wrapper keeps recording on both paths.

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_cell_warmed_total` | Counter | — | Cells whose `ServerCell.Unload` was short-circuited into the warm map. On its own this is just deferred work; it only becomes a saving when the cell is rewarmed rather than evicted. |
| `storm_cell_rewarmed_total` | Counter | — | Warm cells re-attached because a player came back into influence — each one a disk read, parse and `RecalcAll2` that never happened. A rewarm rate close to the warm rate means players are pacing a boundary and the feature is earning its memory. Steady warms with near-zero rewarms means you are holding cells nobody returns to, and the cap is the only thing stopping that from growing. |
| `storm_cell_warm_count` | Gauge | — | Cells currently held warm. Pinned at `-Dstorm.cells.maxWarm` means the cap is binding, and from that point every additional warm costs an eviction. |
| `storm_cell_warm_evicted_total` | Counter | — | Warm cells destructively unloaded because the set exceeded the cap. An eviction is strictly worse than never having warmed the cell — it pays the detach and the re-attach *before* the vanilla unload it was trying to avoid. A sustained eviction rate means the cap is too low for how far apart your players are, or the player spread is simply beyond what warming can help with. Eviction is distance-aware: the first 8 LRU-ordered candidates are scanned for one no player is within 2 cells of, and at most 4 cells are evicted per tick. |
| `storm_cell_warm_evict_near_skip_total` | Counter | — | LRU-head eviction candidates spared because player influence was within 2 cells — a farther cell was evicted instead of one likely to be rewarmed moments later. A high rate relative to `storm_cell_warm_evicted_total` means the LRU order alone would have been thrashing (evicting exactly the cells players are pacing next to) and the distance-aware pass is doing real work. |
| `storm_cell_warm_over_cap` | Gauge | — | Warm cells above `-Dstorm.cells.maxWarm` after this tick's evictions. Non-zero is normal for a few ticks after a warm burst (the per-tick eviction cap of 4 spreads the unload cost); a value that stays high means cells are going warm faster than 4/tick can retire them. |
| `storm_cell_warm_eligibility_fail_total` | Counter | `reason={soft_reset,no_server_map,save_or_quit_queued,chunk_soft_reset}` | `ServerCell.Unload` calls where the predicate refused to warm and vanilla destructive unload ran. All four reasons are correct-by-design refusals around a save, a quit or a soft reset rather than errors, so a burst of `save_or_quit_queued` at autosave time is expected. Sustained `chunk_soft_reset` outside a reset window is not, and `no_server_map` outside startup/shutdown should never appear. |
| `storm_cell_warm_duration_seconds` | Histogram (native) | — | How long a cell stayed warm before leaving the warm map — observed on both exits, rewarm and eviction, so it covers every warmed cell that has finished. This distribution is how you size the cap: if the bulk of rewarms land within a few seconds, a small cap suffices, and a long tail is memory being held for nothing. |
| `storm_cell_warm_op_duration_seconds` | Histogram (native) | — | Main-thread time inside one `warm()`: dead-body drain, then per-chunk animal drain and detach from `MapCollisionData`, both population managers and the pathfinder. Charged to the tick that would have unloaded the cell, so this is the cost warming *adds* to `pz_server_map_post_update_call_duration_seconds`. |
| `storm_cell_rewarm_op_duration_seconds` | Histogram (native) | — | Main-thread time inside one `rewarm()`: chunk re-attach, animal and dead-body restore, and one `OnChunkRewarmedEvent` per non-null chunk (up to 64 per cell, so a slow mod handler shows up here). Compare it against `pz_server_cell_load2_call_duration_seconds`, the cold path it replaces — if it is not dramatically cheaper, warming is not paying for itself. |

Useful PromQL:

```promql
# is warming paying off? rewarms as a share of everything that left the warm map
rate(storm_cell_rewarmed_total[1m])
  / (rate(storm_cell_rewarmed_total[1m]) + rate(storm_cell_warm_evicted_total[1m]))

# the cap is binding and every warm now costs an eviction
rate(storm_cell_warm_evicted_total[1m]) > 0

# net main-thread cost of warming, seconds of tick per second
rate(storm_cell_warm_op_duration_seconds_sum[1m])
  + rate(storm_cell_rewarm_op_duration_seconds_sum[1m])

# how long cells actually survive warm — the input to sizing -Dstorm.cells.maxWarm
histogram_quantile(0.90, rate(storm_cell_warm_duration_seconds[1m]))
```

### Entity removal index (StormEntityIndex)

Tallies for the O(1) indexed removal from the engine's entity arrays — the global
`EngineEntityManager.entities` plus every `EntityBucket`'s own array
(`Storm.EntityRemoveFastPath`). Tallies are plain non-atomic `long`s read at scrape time via
`CounterWithCallback` (main-thread writers only — the engine mutates its entity array exclusively
on the server main thread). A non-zero `mismatch` count means the index desynced, the self-check
caught it, and the fast path latched itself off permanently — grep the server log for
"StormEntityIndex self-check MISMATCH". The applied kill-switch value is exposed as
`storm_entity_remove_fast_path` alongside the other sandbox gauges.

| Name | Type | Labels | What |
|------|------|--------|------|
| `pz_entity_array_removes_total` | CounterWithCallback | `path={fast,scan,mismatch,vanilla}` | Removals from the indexed entity arrays (global + per-bucket): `fast` = indexed O(1) swap-with-last; `scan` = Storm's inline linear scan (index miss around a kill-switch toggle, or an equals-based call); `mismatch` = self-check failure (latches the fast path off); `vanilla` = fell through to the vanilla scan (kill switch off or failure latch). |
| `storm_entity_index_size` | GaugeWithCallback | — | Entities currently tracked by the global-array removal index — mirrors the engine's global entity array size while the fast path is active. |
| `storm_entity_index_tracked_arrays` | GaugeWithCallback | — | Entity arrays carrying a removal index in the current world generation: 1 for the global array plus 1 per `EntityBucket`. |

### SyncIsoObject relevancy gate (StormSyncIsoObjectGate)

Tallies for the per-connection relevancy gate on the server-side `syncIsoObject` broadcast loops —
base `zombie.iso.IsoObject` plus the `IsoWorldInventoryObject`, `IsoBarricade`, and
`IsoLightSwitch` overrides. Vanilla sends every SyncIsoObject full-state packet to every
connection; the gate adds vanilla's own `IsoDoor` precedent
(`isFullyConnected() && isRelevantTo(x, y)`) per recipient. The gate is always on; it reverts to
vanilla broadcast permanently if the gated path ever throws. `suppressed` counts only sends
vanilla *would* have made that the gate dropped — `IsoLightSwitch`'s already-vanilla-gated
`source == null` branch contributes to `sent` only, so the suppression ratio is a true measure of
the gate's traffic saving. Tallies are plain non-atomic `long`s read at scrape time via
`CounterWithCallback` (main-thread writers only).

| Name | Type | Labels | What |
|------|------|--------|------|
| `pz_sync_iso_object_calls_total` | CounterWithCallback | `target={iso_object,world_inventory,barricade,light_switch}`, `path={gated,vanilla}` | `syncIsoObject` invocations by executed path (`vanilla` = failure latch tripped). `iso_object` is the base method, i.e. every subclass without its own override (hutches, generators, rain barrels via the fluid sync). |
| `pz_sync_iso_object_packets_total` | CounterWithCallback | `target` (as above), `outcome={sent,suppressed}` | Per-connection send decisions inside the gated loops: packets actually sent vs sends vanilla would have made that the relevancy gate dropped. |

### GameEntity broadcast gate (GameEntityBroadcastGateMetrics)

The same idea as the SyncIsoObject gate, applied to the broadcast branch of
`GameEntityNetwork.sendPacketData` (`StormGameEntityBroadcastGate`). Vanilla hands every GameEntity
packet to `INetworkPacket.sendToAll`, whose only filter is `isFullyConnected()` — so every
`CraftLogicSync` progress tick (re-sent every 1000 ms per running station), every `SyncGameEntity`
component dump and every using-player change goes to every connection whether or not that client
holds the entity's chunk. At 103 players on ATF that stream measured ~1.06 MB/s at ~1080 pkt/s, the
single largest outbound packet count on the server. The gate applies vanilla's own `sendToRelative`
precedent per recipient: `isFullyConnected() && isRelevantTo(x, y)`.

Only `GameEntityType.IsoObject` entities are gated, because a placed world object's own square is a
meaningful relevancy anchor. Inventory items, vehicle parts, moving objects and meta-entities keep
the vanilla broadcast and are counted as `bypassed`, as is an `IsoObject` entity that has no square
yet. Targeted (non-broadcast) sends never reach the gate and are not counted at all.

The gate is always on and latches back to vanilla permanently if the gated body throws. Tallies are
plain non-atomic `long`s read at scrape time via `CounterWithCallback` — `sendPacketData` is
reached only from engine update and inbound-packet processing, both main-thread.

| Name | Type | Labels | What |
|------|------|--------|------|
| `pz_game_entity_broadcast_calls_total` | CounterWithCallback | `path={gated,bypassed,vanilla}` | Broadcast invocations by executed path. `gated` = the relevancy replacement ran and vanilla's `sendToAll` was skipped; `bypassed` = fell through to vanilla by design (non-`IsoObject` entity type, no square, or a vanilla validation-warn case); `vanilla` = fell through because the failure latch tripped. `vanilla` should be flat at 0 — once it starts moving the gate is off for the remainder of the session and the log has the throwable. |
| `pz_game_entity_broadcast_packets_total` | CounterWithCallback | `outcome={sent,suppressed}` | Per-connection send decisions inside the gated path. `suppressed` is traffic vanilla would have put on the wire that the gate dropped, so `suppressed / (sent + suppressed)` is the bandwidth saving directly. Suppression is safe because a client out of range has no chunk holding the entity: current state rides in the chunk payload when the chunk streams back in, and the next 1 s `CraftLogicSync` arrives as soon as the client becomes relevant again. |

Useful PromQL:

```promql
# share of GameEntity broadcast sends the relevancy gate dropped
sum(rate(pz_game_entity_broadcast_packets_total{outcome="suppressed"}[1m]))
  / sum(rate(pz_game_entity_broadcast_packets_total[1m]))

# the gate has latched off — every broadcast is vanilla sendToAll again
rate(pz_game_entity_broadcast_calls_total{path="vanilla"}[1m]) > 0
```

### Net-data drain cap (NetDataMetrics)

Counters for the per-spin inbound packet drain cap (`Storm.NetDataCapMs` sandbox option) wrapped
around `GameServer.mainLoopDealWithNetData`. `pz_netdata_call_duration_seconds` in the composite
table above times the same advice.

| Name | Type | Labels | What |
|------|------|--------|------|
| `pz_netdata_dropped_total` | Counter | — | Inbound packets dropped because the per-spin drain cap was exceeded. Dropped for good: the packet was already dequeued and ACKed by RakNet, is never processed, and is discarded back to the pool. Non-zero during a reconnect storm means the cap is engaging; a sustained non-zero rate under steady-state load means `Storm.NetDataCapMs` is too tight. |
| `pz_netdata_deferred_total` | Counter | — | **Deprecated** misnomer for `pz_netdata_dropped_total` (nothing is deferred — the packet is dropped). Publishes the identical count for dashboard/alert backwards compatibility; migrate queries to the new name. |
| `pz_netdata_vehicle_request_exempt_total` | Counter | — | `VehicleRequest` packets processed despite the engaged cap. VehicleRequest is the only inbound path that produces a `VehicleFullUpdate` for a client missing a vehicle, so the cap exempts it — dropping it strands invisible cars. Rate is bounded by the client-side 100 ms request batching. |
| `pz_netdata_type_exempt_total` | Counter | `type` | Packets processed despite the engaged cap because their type is on the one-shot allowlist (`CreatePlayer`, `ConnectCoop`, `TimeSync`, `RequestData`, `NetTimedAction`, `BuildAction`, `FishingAction`). The vanilla client sends these exactly once with no retry and no periodic stream regenerates the state, so a single drop wedges the player: respawn stuck forever with no timeout (and on a fully-connected connection the stalled-connection reaper never fires), server clock 0 all session, action queue frozen ≥ 30 min, world download stalled. |
| `pz_netdata_prejoin_exempt_total` | Counter | `type` | Packets processed despite the engaged cap because their connection had not completed the join handshake (`UdpConnection.isFullyConnected()` false — flips only in `receivePlayerConnect`). The login funnel (Login, LoginQueueRequest, Checksum, LoginQueueDone, PlayerConnect) is one-shot and never retried by the vanilla client, so dropping any of it silently strands the join until the stalled-connection reaper kills the client. A non-zero rate means the cap engaged while someone was joining; each increment is a join that would have failed. |

### Chunk streaming (ChunkStreamMetrics)

The client-download path, end to end. Vanilla's supply side is far narrower than its demand side:
`GameServer` calls `PlayerDownloadServer.update()` once per connection per tick, and that call
dispatches **at most one** `ClientChunkRequest` — capped at 20 chunks — and only when that
connection's single worker thread has finished the previous one. At the 10 Hz server tick that is a
hard ceiling of 200 chunks/sec/player. Demand has no cap at all: `WorldStreamer.updateMain()` packs
every chunk the client currently wants into one `RequestZipList`, which `RequestZipListPacket.parse`
splits into as many 20-chunk requests as it takes and appends to `ccrWaiting`. A driving player
generates demand faster than that drains, so the backlog grows until they stop.

Nothing reported that backlog before this class. The one pre-existing metric on the path,
`pz_player_download_server_update_call_duration_seconds`, times the dispatch call — which does
nothing at all while the worker is busy, so a fully saturated peer read as *cheap*.

Per-peer gauges carry a `username` label, bounded by concurrent players and zeroed when a peer
departs. The cumulative counters deliberately do not: on a counter that label would grow with
lifetime unique logins.

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_chunk_stream_backlog_requests` | Gauge | `username` | `ClientChunkRequest`s queued in `ccrWaiting` for one peer, i.e. the backlog measured in dispatch slots. Excludes requests whose chunks have all been drained by cancellation or dedupe, which would otherwise read as phantom backlog until the queue reaps them. |
| `storm_chunk_stream_backlog_chunks` | Gauge | `username` | Individual chunks queued across every request for one peer. Divide by the served rate for delivery lag in seconds. |
| `storm_chunk_stream_backlog_chunks_max` | Gauge | — | Largest per-peer backlog this tick. Unlabelled companion so alerts can watch the worst-off player without a `topk` over a labelled series. |
| `storm_chunk_stream_worker_busy` | Gauge | `username` | 1 when this peer's worker is mid-request at tick time. While 1, `update()` dispatches nothing for that peer however deep the backlog. |
| `storm_chunk_stream_worker_samples_total` | Counter | `state={busy,ready_backlogged,ready_idle}` | Per-tick worker observations summed over all peers. The busy / ready_backlogged split says whether raising the dispatch rate would help or just move the queue. |
| `storm_chunk_stream_worker_threads` | GaugeWithCallback | — | Live `PlayerDownloadServer*` threads. Vanilla starts one per connection and only joins it in `destroy()`. |
| `storm_chunk_stream_requested_total` | Counter | — | Chunks asked for across all `RequestZipList` packets. The demand side. |
| `storm_chunk_stream_request_packet_chunks` | Histogram (native) | — | Chunks per `RequestZipList` packet. |
| `storm_chunk_stream_batch_chunks` | Histogram (native) | — | Chunks in each dispatched batch, capped at 20 by `ClientChunkRequest.isChunksFilled()`. |
| `storm_chunk_stream_dispatched_total` | Counter | `source={hot,cold}` | Chunks leaving the queue. `hot` was resident in `ServerMap` and serialized on the main thread; `cold` was not, so the worker must find a save file or park the request in `queueUntilGenerated` until the chunk exists. |
| `storm_chunk_stream_batch_duration_seconds` | Histogram (native) | — | Worker time for one batch. This is how long a peer's single dispatch slot stays held. |
| `storm_chunk_stream_sent_total` | Counter | — | Chunks actually written to the wire. |
| `storm_chunk_stream_sent_bytes_total` | Counter | — | Compressed chunk bytes sent. |
| `storm_chunk_stream_sent_uncompressed_bytes_total` | Counter | — | Pre-compression bytes. Ratio against the compressed counter is the effective Deflate level-3 ratio. |
| `storm_chunk_stream_sent_chunk_bytes` | Histogram (native) | — | Compressed size distribution of individual chunks. |
| `storm_chunk_stream_compress_duration_seconds` | Histogram (native) | — | Worker time in `compressChunk`. |
| `pz_chunk_save_loaded_call_duration_seconds` | Histogram (native) | `caller={download,save}` | Time in `IsoChunk.SaveLoadedChunk`, the serialize step for a resident chunk. Two unrelated callers share the method, so always filter on `caller`. `download` is `PlayerDownloadServer.update` on the main thread, up to 20 times per connection per tick — the chunk-streaming path, charged to the tick rather than the worker. `save` is `ServerCell.Save` going to disk via `ServerChunkLoader.addSaveLoadedJob`; it fires 64 chunks per loaded cell on every `SaveAll` and would otherwise swamp the download series by orders of magnitude. |
| `storm_chunk_stream_serialized_total` | Counter | `caller={download,save}` | Chunks serialized by `SaveLoadedChunk`, split the same way as the histogram above. `caller="download"` is the resident chunks serialized on the main thread for the stream; `caller="save"` is persistence and has nothing to do with chunk streaming. |
| `storm_chunk_stream_not_required_total` | Counter | `same_on_server={true,false}` | `NotRequiredInZip` replies. `true` means the client's copy already matched; `false` means it is being told the chunk is not coming. |
| `storm_chunk_stream_duplicate_requests_total` | Counter | — | Queued requests dropped because a newer request for the same `wx,wy` was already waiting. Re-asks are driven by the server's `ChunkNotReady` replies or the chunk map re-wanting a cancelled chunk. The best server-side proxy for client-perceived stall. Also increments `storm_chunk_stream_not_required_total{same_on_server="false"}`. |
| `storm_chunk_stream_peer_cell_holes` | Gauge | `username` | Cells still flagged unloaded in the server's copy of one peer's cell mirror, summed over all four split-screen indices. Read from `UdpConnection.getLoadedCell(index).loaded`, the same `boolean[]` the client's `BaseVehicle.isInvalidChunkAhead` brake consults — one boolean per 64x64 tiles. The server pushes the array on change, so its copy is never fresher than the client's: treat this as a lower bound. Non-zero around a driving player is the bookkeeping brake, not a real chunk shortfall. |
| `storm_chunk_stream_peer_cell_holes_max` | Gauge | — | Largest per-peer hole count this tick. Unlabelled companion for alerting without a `topk`. |
| `storm_chunk_stream_peer_brake_cells` | Gauge | `username` | Unloaded mirror cells inside the 3x3 cell window around the peer's own cell. The mirror spans 256x256 tiles or more, but `BaseVehicle.isInvalidChunkAhead` only looks 16 tiles ahead, so only adjacent cells can actually force the brake. Sharper than `storm_chunk_stream_peer_cell_holes`, which sits high on distant edge cells that never matter. |
| `storm_chunk_stream_peer_brake_cells_max` | Gauge | — | Largest per-peer brake-cell count this tick. Unlabelled companion for alerting without a `topk`. |
| `storm_chunk_stream_peer_brake_seconds` | Gauge | `username` | How long this peer has continuously had at least one unloaded cell in brake range; resets to 0 once it clears. The reported symptom measured directly — a driver whose cell ahead never loads has the brake held for exactly this long. Backlog and hole counts say how bad the plumbing is; this says how long someone could not move, so it is the number a fix has to bring down. |
| `storm_chunk_stream_peer_brake_seconds_max` | Gauge | — | Longest current brake episode across all peers. Unlabelled companion for alerting without a `topk`. |
| `storm_chunk_stream_runway_tiles` | Gauge | `username` | Tiles from the peer to the first cell ahead of them that `ServerMap` has *not* hydrated, marched along their vehicle's velocity vector and capped at 8 cells (512 tiles). Every other gauge in this table reports congestion the player has already hit; this is the margin left before they hit it. Reads `ServerMap` rather than the peer's `ClientServerMap` mirror on purpose — the mirror says what the client has been *sent* (that is `storm_chunk_stream_peer_brake_cells`), this says whether the world in front of them exists on the server at all. Sampled every 16 tiles, so a ray that only clips a cell corner can miss it. **Reads as the cap when the peer is stationary or on foot**, where there is no heading to march along — always gate on `storm_chunk_stream_speed_tiles_per_second`. |
| `storm_chunk_stream_runway_tiles_min` | Gauge | — | Shortest runway across all peers this tick. Unlabelled companion for alerting without a `bottomk`. Sits at the cap while nobody is driving. |
| `storm_chunk_stream_speed_tiles_per_second` | Gauge | `username` | Horizontal speed of the vehicle the peer is in; 0 on foot or parked. One tile is one metre, so 16.7 is 60 km/h. Read from `BaseVehicle.jniLinearVelocity`, which the server does not simulate but *does* write from every authorized `VehiclePhysicsPacket`, so it tracks the client's own physics at that packet's 150 ms cadence. Exported raw rather than pre-divided into a seconds-of-runway gauge so a parked car divides to `+Inf` instead of a made-up number, and so stalls can be split by how fast the player was actually going. |
| `storm_chunk_stream_request_residency_total` | Counter | `state={resident,cell_loading,cell_absent,chunk_absent}` | Chunks split by what `ServerMap` had at the moment the request *arrived*. `resident` = a loaded `ServerCell` held the chunk, so the server could have answered immediately. `cell_loading` = the `ServerCell` exists but has not finished hydrating, so the request is early and is answered once hydration lands. `cell_absent` = there is no `ServerCell` at all — nothing is loading it, so unless a save file exists on disk the request parks in `queueUntilGenerated` for a chunk the server never even started. `chunk_absent` = the cell is loaded but that slot is empty or the chunk in it is not itself loaded, which should be rare and points at the cell rather than the stream. Splitting them matters because the fixes are opposite: `cell_loading` wants faster hydration, `cell_absent` wants the cell requested sooner (warmer reach, lookahead), and only `cell_absent` explains a player who is *stuck* rather than merely waiting. Same test as `storm_chunk_stream_dispatched_total` but taken at request time rather than dispatch time; the gap between the two non-`resident` rates is demand that hydration caught up with while the request sat queued. |
| `storm_chunk_stream_queue_wait_seconds` | Histogram (native) | `kind` (always `fresh` since 42.20.3 removed the chunk retry ladder; label kept for dashboard continuity) | Time a `ClientChunkRequest` spent between being allocated for a peer and the download worker starting on it — the delay imposed by the one-request-per-connection-per-tick rule, isolated from how long the work itself takes (`storm_chunk_stream_batch_duration_seconds`). When a driver stalls, this is where the seconds go. |
| `pz_player_download_dedupe_call_duration_seconds` | Histogram (native) | — | Main-thread time in `removeOlderDuplicateRequests`. The scan is waiting-requests x 20 x waiting-requests x 20, so its cost rises with the square of the backlog it exists to drain. |

Useful PromQL:

```promql
# is the one-request-per-tick rule the limiter, or the worker?
sum(rate(storm_chunk_stream_worker_samples_total{state="ready_backlogged"}[1m]))
  / sum(rate(storm_chunk_stream_worker_samples_total[1m]))

# demand vs delivery, chunks/sec
rate(storm_chunk_stream_requested_total[1m]) - rate(storm_chunk_stream_sent_total[1m])

# share of dispatches the server could not answer from memory
sum(rate(storm_chunk_stream_dispatched_total{source="cold"}[1m]))
  / sum(rate(storm_chunk_stream_dispatched_total[1m]))

# worst player's coarse-mirror holes: how much of the brake is bookkeeping
max_over_time(storm_chunk_stream_peer_cell_holes_max[1m])

# the symptom itself: longest stretch anyone has been held by the brake
max_over_time(storm_chunk_stream_peer_brake_seconds_max[1m])

# seconds of hydrated world left in front of each moving driver. compare against
# the cell hydration histogram below: a driver with less runway than it takes to
# hydrate one cell is going to stall no matter how fast the download side runs
storm_chunk_stream_runway_tiles / storm_chunk_stream_speed_tiles_per_second
  and storm_chunk_stream_speed_tiles_per_second > 1

# ...and what one cell actually costs to hydrate, for that comparison
histogram_quantile(0.9, rate(storm_chunk_hydration_cell_total_seconds[5m]))

# was the terrain already hydrated when asked for? (vs. by the time it was dispatched)
sum(rate(storm_chunk_stream_request_residency_total{state!="resident"}[1m]))
  / sum(rate(storm_chunk_stream_request_residency_total[1m]))

# the stuck case, not the merely-slow one: no ServerCell exists, so nothing is
# loading it and the request parks until world generation produces the chunk
sum(rate(storm_chunk_stream_request_residency_total{state="cell_absent"}[1m]))
  / sum(rate(storm_chunk_stream_request_residency_total[1m]))

# how much of the delay is queueing rather than work
histogram_quantile(0.95, sum by (le, kind) (rate(storm_chunk_stream_queue_wait_seconds[1m])))
```

### World hydration (ChunkHydrationMetrics)

Depth of the server's own cell pipeline — the upstream cause of every `cold` dispatch above. A
download worker cannot generate a chunk; it can only stat the save file, which does not exist for
ground no one has visited. The only thing that produces that chunk is this pipeline:
`ServerMap.preupdate` enqueues a whole `ServerCell` (64 chunks) onto `ServerChunkLoader`, the single
`LoadChunk` thread reads or generates all 64, the single max-priority `RecalcAll` thread makes three
full passes over them, and only then does the main thread run `Load2` and set `isLoaded`. Until that
completes `ServerMap.getChunk` returns null for all 64 and every client request for them is
unanswerable.

Both worker stages are single-threaded with unbounded queues, and there is no per-tick budget
anywhere in the path — `preupdate` submits every pending cell at once and `Load2`s every
recalc-complete cell in the same tick — so a spike in `cells_pending` lands on the main thread whole.

Every field read here is private or package-private in PZ, so sampling goes through cached
reflection that resolves once and latches off on failure. A PZ rename costs one warning and a flat
zero on these series, never an exception on the tick path.

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_chunk_hydration_cells_pending` | Gauge | — | Cells wanted but not finished loading (`ServerMap.toLoad`). Multiply by 64 for an upper bound on chunks the server physically cannot serve right now, however short the download queue is. |
| `storm_chunk_hydration_cells_loaded` | Gauge | — | Cells currently resident (`ServerMap.loadedCells`). Churn against `cells_pending` is a player turning around into cells that were just unloaded. |
| `storm_chunk_hydration_queue_depth` | Gauge | `stage={load_in,load_out,recalc_in,recalc_out}` | Cells waiting at each `ServerChunkLoader` stage. Depth at `load_in` or `recalc_in` means world hydration is the bottleneck and extra download bandwidth will not help. |
| `storm_chunk_disk_read_duration_seconds` | Histogram (native) | `caller={download,other}` | Time in `IsoChunk.SafeRead`. It holds a **fair** per-chunk read-write lock across the whole file read, so a queued writer on a hot chunk parks every later reader including all download workers. |
| `storm_chunk_checksum_duration_seconds` | Histogram (native) | `caller={download,other}` | Time in `ChunkChecksum.getChecksum`. The whole body — including a full file read through a shared `CRC32` and a shared 1 KB buffer on a miss — runs inside one static monitor, so every download worker plus the save thread serialise on it. One player's cold checksum stalls every other player's worker. |
| `storm_chunk_checksum_calls_total` | Counter | `caller={download,other}` | Calls to `getChecksum`. With the duration histogram this gives total time the global monitor is held per second. |
| `storm_chunk_hydration_cell_duration_seconds` | Histogram (native) | `stage={load,recalc}` | Wall-clock a cell spends in each hydration stage. `load` spans `ServerChunkLoader.addJob` → `addRecalcJob` and covers the single **LoadChunk** thread reading or generating all 64 chunks. `recalc` spans `addRecalcJob` → the `Load2` that returns true, covering the single max-priority **RecalcAll** thread plus the wait for the main thread to pick the cell back up. Each stage is one thread deep behind an unbounded queue, so under load these mostly measure queueing rather than work — but they must be split before optimising, because a disk/worldgen bottleneck and a recalc bottleneck need opposite fixes. |
| `storm_chunk_hydration_cell_total_seconds` | Histogram (native) | `content={disk,generated}` | End-to-end `addJob` → loaded, split by whether any of the cell's 64 chunks went through `IsoChunk.LoadBrandNew` (`generated`) instead of being read back from a save file (`disk`). Worldgen costs far more than a read, so a driver heading into never-visited map sits on a different latency curve than one retracing roads already on disk. This is the series that says which case a stall is. |
| `storm_chunk_hydration_oldest_pending_seconds` | Gauge | — | Age of the oldest cell in `ServerMap.toLoad` that has already been handed to a worker. The two histograms above only observe cells that **finish**, which makes them blind to the two cases that matter most: a player parked waiting on a cell right now, and a cell stranded permanently by a swallowed exception in `ServerChunkLoader.run` — that leaves `startedLoading`/`doingRecalc` set, so `preupdate` never re-dispatches it and `ServerMap.getChunk` answers null for its 64 chunks for the rest of the server's life. A sawtooth is normal load; a line climbing without bound is a stranded cell. |
| `storm_chunk_hydration_cancelled_cells_total` | Counter | `stage={before_dispatch,in_flight}` | Cell loads cancelled because no player is near them any more. `before_dispatch` was still queued, so nothing was wasted. `in_flight` had a worker on it, so up to 64 chunks of disk reads or worldgen are discarded — and a cell holding brand-new chunks cannot be cancelled at the recalc stage at all, so it pays the full three-pass recalc before being thrown away. A driver outrunning hydration generates this continuously: cells requested, half-loaded, abandoned on the way past, requested again on the way back. High `in_flight` means the hydration threads are busy on work nobody will see, which is why the cells actually ahead of the player stay pending. Counted only on Storm's own `postupdate` bodies — cell warming, or the unload budget when `Storm.CellUnloadBudgetPerTick > 0`. Set the budget to 0 with warming off, or trip either failure latch, and the uninstrumented vanilla body runs instead: cancellation carries on, this counter flatlines, and zero then means *unmeasured*, not *none*. |

Counted from Storm's re-implementations of `ServerMap.postupdate` — `StormCellUnloadBudget` and,
under `-Dstorm.cells.keepWarm=true`, `StormCellWarmer`. **Coverage hole:** with
`CellUnloadBudgetPerTick = 0`, or after the budget latches off on error, vanilla's own `postupdate`
body runs the cancel branch instead and this counter stays flat. The budget defaults to 2, so that
is an opt-out, not the normal case.

The three stage boundaries are stamped from advices on `ServerChunkLoader.addJob`/`addRecalcJob` and
`ServerCell.Load2`. All three run on the server main thread and each has exactly one call site in
`ServerMap.preupdate`, so the stamps cannot interleave or double-count. `Load2` is gated on its
return value: it drains the recalc queue on every call and only does real work — `RecalcAll2` plus
`loadVehicles` — on the `true` path, which is the minority by call count. The timestamps live in two
fields injected onto `ServerCell` rather than in a side map because `ServerCell` is never pooled;
`IsoChunk`, by contrast, is recycled through `IsoChunkMap.chunkStore` and cannot carry a timestamp
without a generation counter.

```promql
# which single-threaded stage owns the latency — disk/worldgen, or recalc?
histogram_quantile(0.95, sum by (le, stage) (rate(storm_chunk_hydration_cell_duration_seconds[1m])))

# what worldgen costs over a plain disk read, p95
histogram_quantile(0.95, sum by (le, content) (rate(storm_chunk_hydration_cell_total_seconds[1m])))

# share of hydrated cells that had to be generated — a driver entering virgin map
sum(rate(storm_chunk_hydration_cell_total_seconds_count{content="generated"}[1m]))
  / sum(rate(storm_chunk_hydration_cell_total_seconds_count[1m]))

# someone is waiting on terrain right now; if this only ever climbs, a cell is stranded
storm_chunk_hydration_oldest_pending_seconds

# hydration work thrown away after a worker had already started on it, cells/sec
rate(storm_chunk_hydration_cancelled_cells_total{stage="in_flight"}[1m])
```

### Client chunk streaming (ClientChunkStreamMetrics)

**These series come from the game client, not the server.** Everything above ends at the wire;
this is the other half — decompression, the main-thread hydration budget, the `ChunkNotReady`
retry loop, and the force-brake, none of which are observable from the server.

No new transport was needed. PZ's `prometheusPort` check fires before its server-only gate, so
`StatisticManager.init()` starts the Prometheus HTTP server on a client JVM launched with
`-DprometheusPort=<port>` too — there is no `GameServer.server` check — bound to the same
`PrometheusRegistry.defaultRegistry` that `StormPrometheus.registry()` returns, so Storm's series
simply appear on the client's `/metrics`. `StormLauncher` registers the handler **only** when that
property is set; without it the client would sample every frame into a registry nothing can scrape.

Real players' clients are not scrapeable, so this is instrumentation for a dev/test client
reproducing a stall, not a production signal. The production-side proxy for the same failure is
`storm_chunk_stream_duplicate_requests_total` above — a client re-asking for a chunk still waiting
in the queue, readable from the server with no client instrumentation at all.

Sampling runs on the `OnTickEvenPaused` Lua event, once per frame from `IngameState.updateInternal`
— after `WorldStreamer.updateMain()` has built and sent this frame's request batch and before
`IsoChunkMap.update()` drains the hydration queue, so both sides of the handoff are observed at
their peak. (`OnRenderTick` is the obvious alternative and is wrong: it is gated on
`GameWindow.doRenderEvent`, which Lua sets false on entering a world.) Arrivals are the exception —
they come from `LoadChunkEvent`, which vanilla fires once per chunk inserted and which is the only
exact delivery count obtainable without a patch. The two O(gridWidth²) scans behind the `*_holes`
gauges and `ahead_samples` run every 6th frame (~10 Hz at 60 fps, matching the server tick).

Same reflection discipline as the hydration section above: the private `WorldStreamer` and
`BaseVehicle` fields resolve once, a missing one logs a warning and leaves its series flat at `0`,
and the first sampling exception disables sampling for the rest of the session rather than throwing
on the frame path.

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_client_chunk_queue_depth` | Gauge | `stage={wanted,in_flight,in_flight_net,sent_handoff,cancel_pending,main_to_ws,ws_to_main,hydration}` | Chunks or requests sitting at each stage of the client pipeline. `main_to_ws` = `chunkRequests0`, handed to the streamer thread and not picked up yet; `wanted` = `chunkRequests1`, known to be needed but not yet asked for; `in_flight` = `pendingRequests1`, asked for and unanswered; `ws_to_main` = `mainThreadRequestQueue`, built requests waiting for `updateMain` to pack them into a `RequestZipList`; `sent_handoff` = `sentRequests`, packed and sent but not yet on the network-side list; `in_flight_net` = `pendingRequests`, where replies are matched; `cancel_pending` = `waitingToCancelQ`; `hydration` = `IsoChunk.loadGridSquare`, fully decompressed and waiting on the main thread to insert it. Backlog in `wanted` means the streamer thread is the limiter, in `in_flight` the server is, in `hydration` the main-thread drain budget is. The streamer polls at 20 ms while `in_flight` is non-zero and ~140 ms while it is empty, so a newly wanted chunk can wait well over a frame before it even becomes a request. |
| `storm_client_chunk_hydration_budget` | Gauge | — | Chunks `IsoChunkMap.updateInternal` may insert this frame, recomputed as `1 + depth*3/chunkGridWidth`. The only per-frame cap in the whole receive path — nothing throttles reception or decompression. Backlog-proportional by design, so compare it against `queue_depth{stage="hydration"}`: a depth that grows while the budget stays small means the drain is losing ground. |
| `storm_client_chunk_grid_holes` | Gauge | — | Null cells in the local player's `IsoChunkMap` grid — chunks inside the player's own view that are not in the world yet. What the player actually sees missing, as opposed to what the server thinks it sent. |
| `storm_client_server_cell_holes` | Gauge | — | Unloaded flags in the client's `ClientServerMap` mirror of the server's cell grid. One flag covers a whole 8x8-chunk (64x64-tile) `ServerCell`, and the vehicle brake reads this mirror rather than the client's own chunk map — so this being non-zero can brake a car whose surrounding terrain is fully loaded. |
| `storm_client_chunk_streamer_busy` | Gauge | — | 1 when `WorldStreamer.isBusy()` reports outstanding work at sample time. Busy with an empty hydration queue and a non-zero `in_flight` is the signature of waiting on the server. |
| `storm_client_chunk_requesting_large_area` | Gauge | — | 1 while `WorldStreamer.requestingLargeArea` is set (initial world load or teleport). In that mode the streamer caps itself at 40 in-flight requests and stops cancelling out-of-range ones, so backlog and cancel series read differently — use it to exclude load-in from steady-state driving analysis. |
| `storm_client_chunk_grid_width` | Gauge | — | `IsoChunkMap.chunkGridWidth`, the client's view size in chunks. Sets both the hydration budget divisor and the vehicle brake lookahead: >7 looks 2 chunks (16 tiles) ahead, >4 looks 1, and <=4 disables the lookahead entirely. |
| `storm_client_vehicle_speed_kmh` | Gauge | — | Local player's current vehicle speed, 0 on foot. The brake lookahead is a fixed 16 tiles regardless of speed, so this is the x-axis for every stall series below: at 100 km/h those 16 tiles are well under a second of warning. |
| `storm_client_chunk_requests_total` | Counter | — | Requests the client has created, from the delta of `WorldStreamer.requestNumber` (incremented exactly once per `ChunkRequest`). Counts the re-request after a `ChunkNotReady` reply as a new request. The counter restarts when the streamer is replaced on reconnect; a decrease is treated as a restart and contributes nothing. |
| `storm_client_chunk_arrivals_total` | Counter | — | Chunks inserted into the client's world, counted from the vanilla `LoadChunk` event at the end of `IsoChunk.doLoadGridsquare`. The client-side delivery rate to compare against the server's `storm_chunk_stream_sent_total`: a persistent gap is loss, timeout-discard, or backlog. |
| `storm_client_chunk_cancels_total` | Counter | — | Requests cancelled after being issued, summed from the per-frame `WorldStreamer.tempRequests` batch. A chunk is cancelled once no chunk map still references it — usually because the player moved past it before the server answered. High cancels while driving means the stream is delivering terrain the player has already left behind. |
| `storm_client_packet_suppressed_total` | Counter | `type` | Outgoing packets the client dropped for exceeding `MaxPacketsPerSecond`, by packet type. `PacketType.send` calls `cancelPacket()` and returns normally, so the caller never learns the packet went nowhere — and for `type="RequestZipList"` `WorldStreamer.updateMain` has already moved those requests into `sentRequests`, and since 42.20.3 removed the client's resend timer nothing ever retries them — the server never saw the request, so its `ChunkNotReady` timeout never arms, and the chunk stays missing until the chunk map re-wants it. Nothing else exposes this: `PacketsCache` keeps a sliding one-second window of timestamps with no cumulative count, and the only other trace is a `DebugType.Multiplayer` warn. Any non-zero rate on `RequestZipList` is chunk demand evaporating before it reaches the wire. |
| `storm_client_chunk_ahead_samples_total` | Counter | `state={loaded,client_chunk_missing,server_cell_missing,both_missing}` | Per-scan classification of every chunk within 2 chunks of the local player, cross-referencing the coarse `ClientServerMap` cell mirror against the client's own chunk map. `loaded` = both agree it is there; `client_chunk_missing` = the server's cell is flagged loaded but the client has no chunk, a genuine delivery gap; `server_cell_missing` = the client has the chunk but the cell mirror says otherwise, which brakes the car anyway and is pure 64x64-tile granularity loss; `both_missing` = neither. |
| `storm_client_chunk_stall_events_total` | Counter | `mechanism={invalid_chunk_ahead,invalid_chunk_behind,brake_forced,physics_disabled,passenger_gate,player_square_null}` | Times each movement-blocking mechanism engaged, counted on the rising edge. `invalid_chunk_ahead` / `invalid_chunk_behind` = `BaseVehicle.isInvalidChunkAhead/Behind`, the predicate `CarController` brakes on; `brake_forced` = that predicate true while the brake is applied, i.e. the force-brake actually fired (zero engine force, transmission to neutral, throttle decaying); `physics_disabled` = `disableSimulationDueToLackOfSurroundingChunks`, the harder failure where the 3x3 chunk ring is incomplete and the vehicle is made static in the Bullet world; `passenger_gate` = a remote passenger's missing chunk braking the driver (`ClientServerMap` has the chunk, `PassengerMap` refuses it); `player_square_null` = the on-foot equivalent, where `IsoMovingObject` rewinds the character to its last square every tick. |
| `storm_client_chunk_stall_seconds_total` | Counter | `mechanism` (as above) | Wall-clock seconds each mechanism was engaged. This is the metric to optimise: seconds of a drive spent unable to accelerate. Divide by `stall_events_total` for mean episode length, `rate()` it for the fraction of playtime lost. |
| `storm_client_chunk_stall_active` | Gauge | `mechanism` (as above) | 1 while the mechanism is currently engaged. Instantaneous companion to `stall_seconds_total`, for correlating a stall against queue depth and speed on the same timeline. |
| `storm_client_chunk_stall_duration_seconds` | Histogram (native) | `mechanism` (as above) | Length of each completed stall episode. The distribution matters more than the mean: a tail toward 30 seconds implicates the server's chunk-generation deadline, after which it answers `ChunkNotReady` and the wait starts over from scratch. |
| `storm_client_chunk_latency_seconds` | Histogram (native) | — | End-to-end age of a chunk when it lands in the world: from the request's first observation in flight — 42.20.3 removed `ChunkRequest.time`, so the stamp is the sampler's own, at most one frame after the request went on the wire — to the vanilla `LoadChunk` event at the end of `IsoChunk.doLoadGridsquare`. This is the number a streaming fix has to move — every other client series is a proxy for it. Two caveats. Stamps come from the per-frame in-flight walk, so a chunk answered before the next frame is never observed at all; the histogram covers requests that lived at least one frame, and its count over a window is well below `arrivals_total` by design. And a chunk re-requested after a `ChunkNotReady` reply keeps its original stamp, so the observation is total time the player waited for that terrain rather than the age of the attempt that happened to succeed. |
| `storm_client_chunk_oldest_inflight_seconds` | Gauge | — | Age of the oldest unanswered request at sample time, 0 when nothing is in flight. Unlike the latency histogram this cannot be biased by chunks that never arrive, which makes it the honest live stall indicator: it climbs for exactly as long as the server stays silent. Crossing 30 seconds means the server's chunk-generation timeout has fired and answered `ChunkNotReady`, sending the request back around for another full wait. |
| `storm_client_chunk_inflight_timed_out` | Gauge | — | In-flight requests the server has answered `ChunkNotReady`, i.e. `ChunkRequest.flagsUdp` has `CRF_NOT_READY` set: the server's download queue overflowed or the chunk missed its 30-second generation deadline. The streamer thread re-queues these on its next pass, so the observation window is tens of milliseconds and this undercounts — any non-zero value means the server is actively failing to produce chunks; use `timeouts_total` for counting. |
| `storm_client_chunk_timeouts_total` | Counter | — | Retry attempts per chunk, counted when a coordinate already seen in flight reappears with a fresh `requestNumber` — a chunk retried four times before it lands counts four. Since 42.20.3 the retry driver is the server's `ChunkNotReady` reply (download queue overflow or the 30-second generation deadline), plus the rare client-side decompression-failure retry. Each one adds a duplicate request to a queue that is already the bottleneck. Compare against `arrivals_total`: a rising ratio is the signature of the server failing to keep up with chunk generation. |

Reading it:

```promql
# fraction of playtime lost to chunk stalls (1.0 = never able to accelerate)
sum(rate(storm_client_chunk_stall_seconds_total[$__rate_interval]))

# how much of that is 64x64-tile bookkeeping rather than absent terrain — the client HAS
# the chunk, the ClientServerMap cell mirror says otherwise, and the car brakes anyway
sum(rate(storm_client_chunk_ahead_samples_total{state="server_cell_missing"}[$__rate_interval]))
  / sum(rate(storm_client_chunk_ahead_samples_total[$__rate_interval]))

# wasted request volume, requests/sec — re-requests and cancels that never became a chunk
rate(storm_client_chunk_requests_total[$__rate_interval])
  - rate(storm_client_chunk_arrivals_total[$__rate_interval])

# which pipeline stage is the bottleneck
topk(3, avg_over_time(storm_client_chunk_queue_depth[$__rate_interval]))

# mean stall episode length per mechanism
rate(storm_client_chunk_stall_seconds_total[$__rate_interval])
  / rate(storm_client_chunk_stall_events_total[$__rate_interval])

# steady-state driving only: mask out initial world load and teleport
sum(rate(storm_client_chunk_stall_seconds_total[$__rate_interval]))
  and on() storm_client_chunk_requesting_large_area == 0

# how long a chunk actually takes to arrive, typical and tail
histogram_quantile(0.50, rate(storm_client_chunk_latency_seconds[$__rate_interval]))
histogram_quantile(0.99, rate(storm_client_chunk_latency_seconds[$__rate_interval]))

# the resend loop feeding itself: timeouts as a share of chunks actually delivered
rate(storm_client_chunk_timeouts_total[$__rate_interval])
  / rate(storm_client_chunk_arrivals_total[$__rate_interval])

# live stall depth — worst wait on an unanswered request, and how much of the in-flight
# queue is already past the 8s point where its reply gets discarded on arrival
max_over_time(storm_client_chunk_oldest_inflight_seconds[$__rate_interval])
storm_client_chunk_inflight_timed_out
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

### In-game calendar (GameTimeMetrics)

Three scrape-time reads of `GameTime.getInstance()`. They exist so a dashboard can put the in-game
date on the same timeline as everything else: season-driven load (erosion, animal population, sleep
and light cycles) and date-scoped incident reports are far easier to read when the calendar is a
series than when it is something you have to go and ask the server for. PZ stores month and day
zero-indexed internally; these are shifted to the 1-12 and 1-31 a player reads off the in-game
calendar.

Registered via `EventDispatchMetrics`'s static initializer — the same piggyback trick
`ThreadAllocBytesMetrics` uses — so they appear as soon as the first Storm event dispatches rather
than needing a patch of their own.

| Name | Type | Labels | What |
|------|------|--------|------|
| `pz_game_year` | GaugeWithCallback | — | In-game calendar year. |
| `pz_game_month` | GaugeWithCallback | — | In-game month, 1-12 (PZ's internal 0-11, plus one). |
| `pz_game_day` | GaugeWithCallback | — | In-game day of month, 1-31 (PZ's internal 0-30, plus one). |

### Performance sandbox knobs (StormPerformanceSandboxMetrics)

Every Storm performance knob, exported as a plain `Gauge` holding its current effective value.
These are configuration rather than measurement, and that is exactly their value: they are the
difference between "the server got slower at 14:20" and "the server got slower at 14:20 because
someone dropped `Storm.ServerFps` to 5". Booleans export as `1`/`0`. Full descriptions of the
options themselves are in [Server Configuration](server-configuration.md).

Each gauge is seeded in a static block to its vanilla default, so a scrape taken before the sandbox
applier has run returns a sane number instead of nothing, and is then re-pushed by its controller's
live setter on every change — sandbox load at `OnServerStarted`, an admin edit, or a test override.
The corollary is that a gauge sitting at its default early in boot only tells you the applier has
not run yet. `storm_netdata_cap_ms` makes this visible: it is deliberately seeded to `0` rather than
to its real 90 ms default.

Note also that these gauges track the *configured* value. A fast path that latched itself off at
runtime after an exception keeps its gauge at `1` — the counter in the fast path's own section is
what reports that, not this table.

| Name | Type | What |
|------|------|------|
| `storm_server_tick_interval_seconds` | Gauge | Main-loop tick interval in seconds — the gate that sets server TPS. Vanilla 0.1 (100 ms / 10 TPS). Derived from the unified `Storm.ServerFps` option as `round(1000/fps)` ms, so it moves in whole-millisecond steps. Read it against `storm_server_tick_duration_seconds`: measured cycle time above this value is the server missing the budget it was given. |
| `storm_server_lock_fps` | Gauge | Applied `PerformanceSettings.getLockFPS()` on the server. Vanilla 10. Also derived from `Storm.ServerFps`, so this, the tick interval and the physics scalar should always agree (10 fps ↔ 0.1 s); a disagreement means one of the three appliers did not run. |
| `storm_iso_physics_server_fps` | Gauge | FPS scalar used inside `IsoPhysicsObject.update()` on the server, which is what scales physics deltas per tick. Vanilla 10, also from `Storm.ServerFps`. Out of step with the real tick rate, thrown and falling objects move at the wrong speed. |
| `storm_animal_los_tick_interval` | Gauge | Per-animal stride for `IsoAnimal.updateLOS()`, in ticks. Vanilla 1 (every tick); `0` disables animal LOS entirely. `Storm.AnimalLOSTickInterval`. This is the direct lever on the animal share of the tick — check it before concluding animals are cheap on this server. |
| `storm_virtual_animal_tick_interval` | Gauge | Stride for the `AnimalZones.updateVirtualAnimals()` pass, in ticks. Vanilla 1; `0` freezes virtual-animal simulation. Executing ticks compensate through `GameTime.perObjectMultiplier`, so raising this changes cost, not the simulated rate. `Storm.VirtualAnimalTickInterval`. |
| `storm_zombie_auth_tick_interval` | Gauge | Per-zombie stride for the *unowned*-zombie ownership rescan in `NetworkZombieManager.updateAuth()`, in ticks. Owned zombies keep vanilla's 2-second gate regardless of this value. Vanilla 1. `Storm.ZombieAuthTickInterval`. |
| `storm_inventory_item_sweep_tick_interval` | Gauge | Stride for the orphaned-item GC sweep in `InventoryItemSystem.update()`, in ticks. Vanilla 1. `Storm.InventoryItemSweepTickInterval`. Raising it defers item cleanup, so watch the heap if you push it far. |
| `storm_zombie_cull_threshold` | Gauge | Live value of *vanilla's* `ZombieConfig.ZombiesCountBeforeDelete`: zombies streamed to one connection before the server culls the surplus. Vanilla 300; `0` disables culling. Storm has no option of its own here and never writes it — this gauge only republishes what the world's sandbox settings say, so change it through the world setup UI or `SandboxVars.lua`. |
| `storm_max_total_zombies` | Gauge | World-wide ceiling on real zombies. `0` = disabled, which is also vanilla's behaviour — vanilla has no global cap of any kind. `Storm.MaxTotalZombies`; see [World-wide zombie ceiling](#world-wide-zombie-ceiling) for what happens when it engages. |
| `storm_server_los_threads` | Gauge | Concurrent ServerLOS worker slots. Default 1, max 16. `Storm.ServerLosThreads`. The pool pre-starts 15 helper threads regardless of this value, so raising it costs no new threads — only the lock contention counted by `storm_serverlos_onsee_locked_total`. Pushed by the setter; `storm_serverlos_threads` is the same number read live at scrape time. |
| `storm_netdata_cap_ms` | Gauge | Per-spin wall-clock cap on `GameServer.mainLoopDealWithNetData`, in **milliseconds** — not the Prometheus base unit, because the gauge mirrors the sandbox option verbatim. `Storm.NetDataCapMs`, default 90; `0` disables the cap. Seeded to `0` at class load, so `0` early in boot does not mean the cap is off. Packets the cap drops are counted by `pz_netdata_dropped_total`. |
| `storm_peer_send_buffer_kick_mb` | Gauge | Per-peer HIGH send-buffer threshold in megabytes, above which the watchdog force-disconnects a peer once the hold window elapses. Default 20; `0` disables the watchdog. `Storm.PeerSendBufferKickMb`; see [per-peer telemetry](#per-peer-network-telemetry--send-buffer-watchdog). |
| `storm_peer_send_buffer_kick_hold_ticks` | Gauge | Consecutive server ticks a peer must stay above that threshold before it is kicked. Default 50 (5 s at vanilla 10 TPS). No effect while the threshold gauge is `0`. `Storm.PeerSendBufferKickHoldTicks`. |
| `storm_screenshot_pieces_per_packet` | Gauge | 24573-byte base64 pieces packed into each `sendClientCommand` packet while a client uploads a `/screenshot` back to the server. Default 4 (~131 KB/packet, safe on a saturated home uplink); hard ceiling 28 (~918 KB/packet, just under vanilla `UdpConnection`'s 1 MB outbound buffer). `Storm.ScreenshotPiecesPerPacket`. |
| `storm_screenshot_upload_kb_per_sec` | Gauge | Throughput cap on a screenshot upload, in KiB/s of base64 wire bytes. Default 128. Sized to leave headroom for RakNet ACK/keepalive traffic so the ~10 s connection timeout does not fire mid-upload. `Storm.ScreenshotUploadKbPerSec`. |
| `storm_screenshot_encode_kb_per_tick` | Gauge | Source KiB the client's single-threaded Lua base64 encoder may consume per client tick during an upload. Default 4. Bounds per-frame encode cost so a large screenshot does not stall rendering for the whole upload. `Storm.ScreenshotEncodeKbPerTick`. |
| `storm_zombie_sight_vehicle_fast_path` | Gauge | `1` = the chunk-windowed fast path for `IsoZombie.isVehicleBetween` is active (default); `0` = vanilla whole-cell vehicle scan. `Storm.ZombieSightVehicleFastPath`. |
| `storm_player_los_fast_path` | Gauge | `1` = the distance-culled `IsoPlayer.updateLOS()` replacement is active (default); `0` = vanilla whole-cell moving-object walk. `Storm.PlayerLosFastPath`; the executed-path split is `pz_player_update_los_calls_total`. |
| `storm_using_player_sweep_fast_path` | Gauge | `1` = the registry-backed `UsingPlayerUpdateSystem.update()` sweep is active (default); `0` = vanilla full iso-bucket scan. `Storm.UsingPlayerSweepFastPath`. |
| `storm_fluid_container_update_fast_path` | Gauge | `1` = the hoisted/reordered `FluidContainerUpdateSystem.updateSimulation()` is active (default); `0` = vanilla per-entity climate re-reads and fluid-list scans. `Storm.FluidContainerUpdateFastPath`. |
| `storm_ecs_class_cache` | Gauge | `1` = the `ClassValue` memoization of `ECSComponent.getECSClass(Class)` is active (default); `0` = vanilla superclass walk on every component lookup. `Storm.EcsClassCache`. |
| `storm_entity_remove_fast_path` | Gauge | `1` = O(1) indexed removal from the engine's global entity array is active (default); `0` = vanilla linear identity scan of the whole array. `Storm.EntityRemoveFastPath`. A self-check failure latches the fast path off without moving this gauge — watch `pz_entity_array_removes_total{path="mismatch"}`. |
| `storm_cell_unload_budget_per_tick` | Gauge | Maximum stale cells `ServerMap.postupdate` may destructively unload per tick; the rest stay loaded and are re-evaluated next tick. Default 2; `0` = vanilla (unload every stale cell in one tick). `Storm.CellUnloadBudgetPerTick`. Has no effect while [cell warming](#cell-warming-stormcellwarmingmetrics) owns `postupdate`. |

Useful PromQL:

```promql
# any fast path switched off by configuration
{__name__=~"storm_.*_fast_path|storm_ecs_class_cache"} == 0

# the three Storm.ServerFps-derived gauges must agree; non-zero means one applier missed
storm_server_lock_fps - storm_iso_physics_server_fps

# the sandbox applier has not run yet (or the cap really is disabled)
storm_netdata_cap_ms == 0
```

### Storm internals

Metrics that measure Storm's own caches / framework code, not PZ behavior.

#### ServerLOS find-data cache

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_server_los_find_data_misses_total` | Counter | — | ServerLOS find-data cache misses. Hits are deliberately not counted — the hit path runs on the LOS hot loop. |
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
| `storm_peer_send_buffer_messages` | Gauge | `username`, `priority={immediate,high,medium,low}` | The same queue counted in **messages** rather than bytes. This is the unit a chunk backlog is denominated in: `SentChunkPacket` fragments every compressed chunk into 1000-byte HIGH/RELIABLE messages, so `priority="high"` is a direct count of chunk fragments sitting between the download worker and the wire. Bytes alone cannot distinguish 40 MB of one broadcast from 40000 queued chunk fragments, and only the second is a streaming problem. |
| `storm_peer_resend_buffer_messages` | Gauge | `username` | Reliable messages awaiting retransmission. Against `storm_peer_resend_buffer_bytes` this gives mean retransmit size, separating many small lost chunk fragments from a few large lost payloads. |
| `storm_peer_bandwidth_limited` | Gauge | `username` | `1` when RakNet is throttling this peer against its **configured** outgoing bandwidth cap rather than against congestion, else `0`. Distinct from `storm_peer_congestion_limited` in the way that matters operationally: this one is a ceiling somebody set and can raise, the other is the link itself backing off. |
| `storm_peer_bps_limit_outgoing` | Gauge | `username` | The configured outbound bytes/second ceiling being applied to this peer. `0` means uncapped. |
| `storm_peer_kicked_send_buffer_total` | Counter | — | Cumulative peers force-disconnected by the watchdog. Unlabelled to keep cardinality bounded; the kicked peer's username/steamId/IP/observed-MB are logged at INFO in `storm/main.log`. |

The two knobs that drive the watchdog — `storm_peer_send_buffer_kick_mb` and
`storm_peer_send_buffer_kick_hold_ticks` — are exported with the rest of the configuration in
[Performance sandbox knobs](#performance-sandbox-knobs-stormperformancesandboxmetrics).

Useful PromQL:

```promql
# top-5 peers by HIGH send buffer
topk(5, storm_peer_send_buffer_bytes{priority="high"})

# peers actively throttled by RakNet congestion control
storm_peer_congestion_limited == 1

# auto-kicks in the last hour
increase(storm_peer_kicked_send_buffer_total[1h])

# seconds of queued chunk data ahead of this peer at its current allowed rate.
# When this exceeds the time to cross a cell boundary at driving speed, the player
# outruns delivery while every upstream chunk metric still looks healthy.
storm_peer_send_buffer_bytes{priority="high"} / storm_peer_bps_limit_congestion

# chunk fragments stuck between the download worker and the wire
topk(5, storm_peer_send_buffer_messages{priority="high"})

# throttled by a configured cap rather than by the link
storm_peer_bandwidth_limited == 1
```

All of the above come from **one** `UdpConnection.getStatistics()` call per peer per tick. That JNI
call allocates a fresh `ZNetStatistics` and takes ~31 native write-backs to populate, so new peer
series should be added as extra field reads off the existing snapshot in `recordAll()` — never as a
second call. It must also stay on the main thread: `RakNetPeerInterface` does no locking on the stats
path, and the chunk download workers are not main-thread.

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
| `awaiting_spawn` | Authenticated and past the queue, character not spawned: downloading chunks, on the character screen, or a "Click Start" camper. `setFullyConnected()` only fires in `receivePlayerConnect`. | yes, unless actively downloading chunks (each request wave re-stamps the reap clock) |
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
| `storm_connection_reap_timeout_seconds` | Gauge | — | Wall-clock budget to finish logging in and spawn. Default `600` (10 min), `-Dstorm.reapStalledConnectionMs`. Time spent actively downloading chunks re-stamps the clock and does not count. |
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

#### Networked entity ID pools (IsoObjectIdPoolMetrics)

PZ addresses networked zombies and animals by a 16-bit `IsoObjectID`, which leaves 65535 usable slots per pool once the `-1` sentinel is reserved. Vanilla's allocator wraps silently on exhaustion and hands out an ID that is already in use, so two entities share an identity and the server starts moving the wrong one; `IsoObjectIDAllocateFixPatch` makes exhaustion fail loudly instead. These series are how you see it coming — a pool climbing toward 65535 on a long-uptime server is a leak worth chasing well before it wraps.

The two sizes are scrape-time callbacks straight off the live maps, so they read `0` before the world exists rather than being absent. The two counters come from `IsoZombieMapInvariant`, checked on the `IsoZombie.update()` exit advice; both are normally flat, and any sustained rate is a real identity bug rather than noise. Registration is triggered by whichever of `IsoZombieUpdateFixPatch` / `IsoObjectIDAllocateFixPatch` loads first.

| Name | Type | Labels | What |
|------|------|--------|------|
| `storm_zombie_id_pool_size` | GaugeWithCallback | — | Live entries in `ServerMap.instance.zombieMap` — the server's authoritative networked-zombie population, and the denominator for the 65535-slot ID pool. Also the honest "how many zombies are loaded right now" number, which is why the zombie-ceiling queries below use it. |
| `storm_animal_id_pool_size` | GaugeWithCallback | — | Live entries in `AnimalInstanceManager.getInstance().getAnimals()`, against the same 65535-slot budget. Animals are longer-lived than zombies and are not culled by the zombie ceiling, so this pool drifts upward more readily. |
| `storm_zombie_map_orphan_fixes_total` | Counter | — | Zombies found updating with a valid `onlineId` that had no entry in `zombieMap`, re-inserted by the invariant check. An orphan is invisible to every lookup that goes through the map — ownership transfer, packet routing, the ceiling sweep — so it desyncs rather than crashes. Non-zero means something removed the map entry without ending the zombie's life. |
| `storm_zombie_map_collision_total` | Counter | — | Zombies whose `onlineId` was already mapped to a *different* live zombie; the newcomer's ID is invalidated rather than allowed to overwrite the incumbent. This is the ID-reuse failure mode the allocate fix exists to prevent, so any increase warrants reading `storm/main.log` around it. |

```promql
# how close either pool is to the 65535-slot ID ceiling
max(storm_zombie_id_pool_size, storm_animal_id_pool_size) / 65535

# identity bugs — both should be flat forever
rate(storm_zombie_map_orphan_fixes_total[1h]) + rate(storm_zombie_map_collision_total[1h]) > 0
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
