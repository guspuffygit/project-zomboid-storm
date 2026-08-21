---
name: add-sandbox-option
description: Add a new Storm server-tunable knob exposed through PZ's vanilla sandbox-option system, with translation, runtime config, Prometheus gauge, and applier wiring. Use when introducing a new performance/behavior knob admins should set via the world setup UI or `<SaveName>.ini`.
---

# Add a Storm sandbox option

Each Storm sandbox option flows through five layers. Skipping any step leaves the option half-wired (unreadable in the UI, the gauge stays at its default, or admins can't discover the knob).

1. **Option declaration** in `media/sandbox-options.txt` — PZ reads this on world load and registers the option on `SandboxOptions.instance`. Storm options use `page = Storm` (buckets them on the "Storm | Performance" tab) and reference a translation key.
2. **Translation** in `media/lua/shared/Translate/EN/Sandbox.json` — `Sandbox_Storm_<Name>` label + `Sandbox_Storm_<Name>_tooltip`. B42's `Translator.tryFillMapFromFile` reads `Sandbox.json` only; the legacy B41 `Sandbox_EN.txt` Lua-table format is silently ignored.
3. **Runtime config class** (e.g. `StormServerLosConfig`, `PeerSendBufferKickConfig`, `ServerLockFpsConfig`) — owns the current value (`AtomicInteger` / `volatile`), exposes a `getX()` reader called from the advice/hot path, and a `setX(int)` setter that clamps, stores, and pushes the new value to the Prometheus gauge. The setter is the **single mutation point**; sandbox apply and tests both funnel through it.
4. **Sandbox applier** — `io.pzstorm.storm.sandbox.StormPerformanceSandboxApplier` reads every Storm option at `OnServerStartedEvent` (server-only via the `GameServer.server` gate) and calls the matching config setter. If the option is missing or wrong-typed the applier logs a warn and leaves the controller at its compiled-in default.
5. **Documentation** in `docs/server-configuration.md` — the public-facing sandbox-options table (under `## Sandbox options (performance knobs)`) is the canonical admin reference. Every new option needs a row here AND its gauge name added to the trailing `storm_*` list, otherwise admins can't discover the knob. **Also update this file** (`.claude/skills/add-sandbox-option/SKILL.md`) — add or update the row in the "Current Storm sandbox options" table at the bottom so future skill invocations see the latest set.

## Step-by-step

### 1. Declare the option

`media/sandbox-options.txt`:

```
option Storm.MyKnob
{type = integer, min = 0, max = 1000, default = 100, page = Storm, translation = Storm_MyKnob,}
```

PZ supports `integer`, `boolean`, `double`, `string`, `enum`; almost every Storm option today is `integer`.

### 2. Add translation

`media/lua/shared/Translate/EN/Sandbox.json` (JSON, not the B41 Lua-table format):

```json
"Sandbox_Storm_MyKnob": "Short UI label",
"Sandbox_Storm_MyKnob_tooltip": "Longer explanation. Mention the vanilla default and what 0/extreme values do."
```

### 3. `DEFAULT_*` constant + setter on the config class

The setter clamps, stores, pushes to the gauge, returns the applied value:

```java
public static final int DEFAULT_MY_KNOB = 100;
private static final AtomicInteger VALUE =
        new AtomicInteger(clamp(Integer.getInteger("storm.myKnob", DEFAULT_MY_KNOB)));

public static int setMyKnob(int n) {
    int clamped = clamp(n);
    VALUE.set(clamped);
    StormPerformanceSandboxMetrics.setMyKnob(clamped);
    return clamped;
}
```

### 4. Prometheus gauge

Add a `storm_*` gauge to `StormPerformanceSandboxMetrics` with a setter. Initialise from the `DEFAULT_*` constant in the static block. Reading the gauge before any setter has run must return the vanilla default, not zero.

### 5. Wire the applier

`StormPerformanceSandboxApplier`:

```java
public static final String OPT_MY_KNOB = "Storm.MyKnob";

// inside onServerStarted:
applyMyKnob();

private static void applyMyKnob() {
    Integer value = readIntOption(OPT_MY_KNOB);
    if (value == null) return;
    MyKnobConfig.setMyKnob(value);
}
```

### 6. Document the option

Add a row to the sandbox-options table in `docs/server-configuration.md` (default, range, effect — match the existing style). Append the new gauge name to the trailing list of `storm_*` gauges in the same section so the "what gauges reflect this" prose stays complete. If the option drives a more involved subsystem (watchdog, retry logic, scheduler), also extend `docs/metrics.md`'s relevant feature subsection.

Then update this skill file — add/update a row in the "Current Storm sandbox options" table below so future invocations of the skill list the option among the live knobs.

## Verify

Set a non-default in the world's `<SaveName>.ini`, start the server, and hit `/metrics` to confirm the `storm_*` gauge reflects the new value.

## Current Storm sandbox options

| Option | Config class / setter | Effect |
|---|---|---|
| `Storm.ServerFps` | `ServerFpsConfig#applyUnifiedFps` | Server fps. Sets `tickIntervalMs = round(1000 / fps)`, `PerformanceSettings.getLockFPS()` on the server, and the `IsoPhysicsObject.update()` fps scalar. Vanilla 10. |
| `Storm.AnimalLOSTickInterval` | `AnimalLOSTickInterval#setTickInterval` | Per-animal stride for `IsoAnimal.updateLOS()`. 1 = vanilla every tick; 0 disables. |
| `Storm.VirtualAnimalTickInterval` | `VirtualAnimalTickInterval#setTickInterval` | Stride for the whole `AnimalZones.updateVirtualAnimals()` pass, with `GameTime.perObjectMultiplier` compensation on executing ticks. 1 = vanilla; 0 freezes virtual animals. Max 16. |
| `Storm.ZombieAuthTickInterval` | `ZombieAuthTickInterval#setTickInterval` | Per-zombie stride for the unowned-zombie ownership rescan in `NetworkZombieManager.updateAuth()`. Owned zombies keep vanilla's 2 s gate. 1 = vanilla; min 1 (never disabled), max 16. |
| `Storm.InventoryItemSweepTickInterval` | `InventoryItemSweepTickInterval#setTickInterval` | Stride for the orphaned-item GC sweep in `InventoryItemSystem.update()`. 1 = vanilla; min 1 (never disabled), max 64. |
| `Storm.MaxTotalZombies` | `StormZombieTotalCap#setMaxTotal` | World-wide ceiling on real zombies, enforced from `ServerTickAdvice`. 0 = disabled; vanilla has no global cap at all. Deletes only zombies passing vanilla's cull predicate widened to every connection. |
| `Storm.ServerLosThreads` | `StormServerLosConfig#setThreads` | Concurrent ServerLOS worker count (1–16). Pool always pre-allocates 15 helpers; this only controls how many receive work per tick. |
| `Storm.NetDataCapMs` | `MainLoopDrainCap#setCapMs` | Per-outer-loop-spin wall-clock cap (ms) on `GameServer.mainLoopDealWithNetData`. 0 disables; default 90. |
| `Storm.PeerSendBufferKickMb` | `PeerSendBufferKickConfig#setKickMb` | Per-peer HIGH send-buffer kick threshold (MB). 0 disables the watchdog. |
| `Storm.PeerSendBufferKickHoldTicks` | `PeerSendBufferKickConfig#setHoldTicks` | Consecutive server ticks the peer's HIGH send buffer must stay above the kick threshold before disconnect fires. |
| `Storm.ReapStalledConnectionSeconds` | `StalledConnectionReaper#setConnectTimeoutSecondsFromSandbox` | Stalled-connection reap budget (login → spawn). Default 600. Ignored when `-Dstorm.reapStalledConnectionMs` is set — the launch flag always wins. Gauge `storm_connection_reap_timeout_seconds` is tick-sampled, so the setter does not push it. |
| `Storm.ZombieSightVehicleFastPath` | `ZombieVehicleOcclusion#setEnabled` | **Boolean** (the first one — `readBooleanOption` exists in the applier for it). Chunk-windowed fast path for `IsoZombie.isVehicleBetween`. Default `true`; `false` restores the vanilla whole-cell vehicle scan. Gauge `storm_zombie_sight_vehicle_fast_path` (1/0). |
| `Storm.PlayerLosFastPath` | `StormPlayerLos#setEnabled` | **Boolean.** Distance-culled, server-stripped fast path for `IsoPlayer.updateLOS()`. Default `true`; `false` restores the vanilla whole-cell moving-object walk. Also auto-reverts permanently if the fast path throws. Gauge `storm_player_los_fast_path` (1/0). |
| `Storm.UsingPlayerSweepFastPath` | `UsingPlayerRegistry#setEnabled` | **Boolean.** Registry-backed sweep for `UsingPlayerUpdateSystem.update()` instead of vanilla's full iso-bucket scan. Default `true`; `false` restores the vanilla scan (safe to flip live — registry maintenance is unconditional). Auto-reverts permanently if the sweep throws. Gauge `storm_using_player_sweep_fast_path` (1/0). |
| `Storm.FluidContainerUpdateFastPath` | `StormFluidContainerUpdate#setEnabled` | **Boolean.** Hoisted/reordered pass for `FluidContainerUpdateSystem.updateSimulation()` (per-pass climate/day-length reads, cheap-guards-first ordering, `FluidType` identity petrol compare). Default `true`; `false` restores the vanilla pass. Auto-reverts permanently if the pass throws. Gauge `storm_fluid_container_update_fast_path` (1/0). |
| `Storm.EcsClassCache` | `EcsClassCache#setEnabled` | **Boolean.** `ClassValue` memoization of the static `ECSComponent.getECSClass(Class)` superclass walk. Default `true`; `false` restores the vanilla walk (always safe to flip live). Auto-reverts permanently if a lookup throws. Gauge `storm_ecs_class_cache` (1/0). |
| `Storm.CellUnloadBudgetPerTick` | `StormCellUnloadBudget#setBudgetPerTick` | Per-tick cap on destructive server-cell unloads in `ServerMap.postupdate()`; stale cells beyond the budget stay in `loadedCells` for later ticks. Default 2; 0 = vanilla unload-everything-now; max 32. Inert while cell warming is active (`Storm.KeepCellsWarm` on, or draining after a live off — `StormCellWarmer#isActive`). Auto-reverts permanently if the budgeted body throws. Gauge `storm_cell_unload_budget_per_tick`. |
| `Storm.EntityRemoveFastPath` | `StormEntityIndex#setEnabled` | **Boolean.** O(1) indexed removal from `EngineEntityManager.entities` instead of vanilla's linear identity scan (~123k entities). Identity self-check before every indexed removal; mismatch latches vanilla permanently. Default `true`; safe to flip live (re-enable triggers one O(n) rebuild). Gauges `storm_entity_remove_fast_path` (1/0), `storm_entity_index_size`. |
| `Storm.VehicleAlphaCheckSkip` | `StormVehicleAlphaCheckSkip#setEnabled` | **Boolean.** Skips `BaseVehicle.couldSeeIntersectedSquare` on the dedicated server (its only consumer, `setTargetAlpha`, is a server no-op). Default `true`; `false` restores the vanilla per-vehicle square walk. Counter `storm_vehicle_alpha_check_skips_total`; gauge `storm_vehicle_alpha_check_skip` (1/0). |
| `Storm.VehicleSoundRelevanceFastPath` | `StormVehicleSoundRelevance#setEnabled` | **Boolean.** Per-tick hoist of the vehicle-only predicates in `vehicleNetworkSound.server.Connection.isRelevant` (snapshot of noisy vehicles at `Manager.update()` entry, vanilla `RelevantTo` per connection). Default `true`; `false` restores the vanilla per-connection scan. Auto-reverts permanently if the fast path throws. Gauge `storm_vehicle_sound_relevance_fast_path` (1/0). |
| `Storm.OverrideMaxPlayers` | `StormMaxPlayersConfig#setOverride` | **Boolean.** Master switch for replacing the `.ini` `MaxPlayers` with `Storm.MaxPlayers` via `ServerOptionsMaxPlayersPatch` on `ServerOptions.getMaxPlayers()`. Default `false` (the `.ini` value passes through untouched). Both halves flow through the single `setOverride(boolean, int)` setter; on effective change it re-pushes `SteamGameServer.SetMaxPlayerCount`. Also applied *early* (before `OnServerStarted`) by `GameServerConnectionCapPatch.UdpEngineFactory` so the boot-time RakNet cap sizes from the override — the applier method `applyMaxPlayersOverride()` is public for that reason. Gauge `storm_max_players_override_enabled` (1/0). |
| `Storm.HutchDirtRatePercent` | `HutchDirtRateFix#setRatePercent` | Rate at which loaded hutches (chicken coops / rabbit hutches) accrue dirt, as a percent of the intended metagame (`IsoHutch.doMeta`) rate. Vanilla rolls dirt per server tick unscaled by game time (~118 dirt/real day at 10 TPS — kills the birds); `HutchDirtRateFixPatch` reverts the per-tick rolls at `update()` exit and re-rolls once per game hour. 100 = intended rate (default), 0 = no dirt while loaded, max 1000. Range 0–1000. Gauge `storm_hutch_dirt_rate_percent`. |
| `Storm.AnimalZoneContainment` | `AnimalZoneContainment#setEnabled` | **Boolean.** Holds livestock inside the player-placed animal zone it belongs to: both `IsoAnimal` obstacle-breaking predicates return false for a contained animal and `pathToLocation` targets are clamped into its own connected zone. Default `true`; `false` restores vanilla, where a hungry animal paths through and destroys player-built pen walls. Gauge `storm_animal_zone_containment` (1/0). |
| `Storm.AnimalZoneLeashDistance` | `AnimalZoneContainment#setLeashDistance` | Tiles outside an animal zone within which a non-wild animal still counts as belonging to it (and is walked back in). Range 0–200, default 20; 0 = only animals standing inside a zone are contained. Ignored while `Storm.AnimalZoneContainment` is off. Gauge `storm_animal_zone_leash_distance`. |
| `Storm.MaxPlayers` | `StormMaxPlayersConfig#setOverride` | Player ceiling used while the override is on; ignored otherwise. Range 1–255 (255 = RakNet's 256-slot wire-index ceiling minus one slot for the login pipeline), default 100 (vanilla hard-caps at 100 — the patch is what makes >100 possible). Live-appliable; lowering never kicks. Gauge `storm_max_players_override`. |
| `Storm.KeepCellsWarm` | `StormCellWarmingConfig#setEnabled` | **Boolean.** Cell warming: stale server cells stay in `loadedCells` with world-system bindings detached instead of being destructively unloaded (`StormCellWarmer` body-replaces `ServerMap.postupdate`). Default `false` = vanilla. Live both ways: **off does not release postupdate** — the advices gate on `StormCellWarmer#isActive()` (enabled OR warm set non-empty), and the warmer drains the set through its eviction pass (`effectiveEvictionCap` = 0 while disabled) before vanilla / the unload budget takes over. The legacy `-Dstorm.cells.keepWarm` flag only seeds the pre-`OnServerStarted` value. Gauge `storm_cell_warming_enabled` (1/0). |
| `Storm.MaxWarmCells` | `StormCellWarmingConfig#setMaxWarmCells` | Bound on the warm set, evicted LRU-with-distance at ≤4/tick. Range 0–1024, default 128, 0 = unbounded. Read every tick by `evictOverBudget`, so live-appliable trivially. Applied *before* `Storm.KeepCellsWarm` in `applyAll` so a first enable starts with the right cap. Legacy seed `-Dstorm.cells.maxWarm`. Gauge `storm_max_warm_cells`. |
| `Storm.LoginQueueMaxConcurrentLoaders` | `LoginQueueEarlyRelease#setMaxConcurrentLoaders` | Total joiners loading into the server at once (released loaders + the login-queue slot-holder). Above 1, the slot is freed at the joiner's WorldMap download request instead of at `LoginQueueDone`. Range 1–32, default 1 = vanilla admission (no early release). Live-appliable. Gauge `storm_login_queue_max_concurrent_loaders`. |
