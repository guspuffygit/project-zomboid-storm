# Main-Thread Performance Logging Plan

## How the current logging works

Each instrumented method follows a three-file pattern:

### 1. Metrics class (`io.pzstorm.storm.metrics.*`)

A singleton with three `AtomicLong` counters (`totalNanos`, `callCount`, `tickCount`) and a daemon reporter thread that snapshots + resets them every 60 seconds. Two public static methods are exposed for advice to call:

- `recordUpdateNanos(long nanos)` — called on every method exit with the elapsed nanos.
- `recordTick()` — called once per server tick from the `MovingObjectUpdateScheduler.startFrame()` advice.

The 60s report logs: `window`, `ticks`, `calls`, `totalMs`, `avgPerTickMs`, `avgPerCallUs`, `avgCallsPerTick`.

Existing metrics classes:
- `AnimalUpdateMetrics` — tracks `IsoAnimal.update()`
- `ChunkRemoveMetrics` — tracks `IsoChunk.removeFromWorld()`

### 2. Advice class (`io.pzstorm.storm.advice.<name>.*`)

A Byte Buddy `@Advice` class with:
- `@Advice.OnMethodEnter` returning `long` (`System.nanoTime()`), guarded by `if (!GameServer.server) return 0L`.
- `@Advice.OnMethodExit(onThrowable = Throwable.class)` that computes elapsed nanos and calls the metrics recorder.

Advice bodies are inlined into the target method by Byte Buddy, so they must use plain imperative Java only.

Existing advice classes:
- `IsoAnimalUpdateTimingAdvice` → `AnimalUpdateMetrics.recordUpdateNanos()`
- `IsoChunkRemoveFromWorldAdvice` → `ChunkRemoveMetrics.recordRemoveNanos()`
- `MovingObjectUpdateSchedulerStartFrameAdvice` → calls `recordTick()` on all metrics classes

### 3. Patch class (`io.pzstorm.storm.patch.performance.*`)

Extends `StormClassTransformer`, passes the target class name to `super()`, and in `dynamicType()` applies the advice via `Advice.to(...).on(ElementMatchers.named(...).and(takesArguments(...)))`.

Registered in `StormClassTransformers` static block via `registerTransformer(new XxxPatch())`.

Existing patch classes:
- `IsoAnimalUpdateTimingPatch` → targets `zombie.characters.animals.IsoAnimal`, method `update()`, 0 args
- `IsoChunkRemoveFromWorldPatch` → targets `zombie.iso.IsoChunk`, method `removeFromWorld()`, 0 args
- `MovingObjectUpdateSchedulerTickPatch` → targets `zombie.MovingObjectUpdateScheduler`, method `startFrame()`, 0 args

### Tick counter wiring

`MovingObjectUpdateSchedulerStartFrameAdvice.onEnter()` calls `recordTick()` on every metrics class. When adding a new metrics class, add its `recordTick()` call to this advice.

---

## Logging candidates

All candidates below are methods called on the main thread that showed significant CPU time in the 60-second JFR recording from the production server (AMD Ryzen 7 9800X3D, 16 HW threads, 112 GB heap, Ubuntu 24.04).

### Tier 1 — Chunk removal / object-list bottleneck

These directly measure the O(n) `ArrayList.contains` bottleneck identified in the JFR analysis.

| # | Target class | Method | Args | JFR % (main) | What it measures |
|---|---|---|---|---|---|
| 1.1 | `zombie.iso.IsoCell` | `addToProcessIsoObjectRemove` | `(IsoObject)` | 13.2% top-of-stack | Per-call cost of the `ArrayList.contains` scan. This is the inner hot loop. |
| 1.2 | `zombie.iso.IsoCell` | `addToProcessIsoObject` | `(IsoObject)` | adjacent | Same pattern, different list. Track together. |
| 1.3 | `zombie.iso.IsoObject` | `removeFromWorld` | `()` | 17.7% inclusive | Outer wrapper for `addToProcess*` calls. Confirms the inner fix accounts for all time. |
| 1.4 | `zombie.network.ServerMap$ServerCell` | `Unload` | `()` | 5.5% inclusive | Per-cell-unload cost. Big spikes = chunk-unload bursts causing lag. |

**Already implemented:**
- `IsoChunk.removeFromWorld()` — `ChunkRemoveMetrics` (sits between 1.3 and 1.4 in the call chain)

### Tier 2 — Animal & player line-of-sight

These measure the LOS computation that dominates main-thread time when many animals/players are present.

| # | Target class | Method | Args | JFR % (main) | What it measures |
|---|---|---|---|---|---|
| 2.1 | `zombie.characters.animals.IsoAnimal` | `updateLOS` | `()` | 22% inclusive | Isolates the LOS slice from the rest of `IsoAnimal.update()`. Critical for validating the LOS optimization. |
| 2.2 | `zombie.characters.IsoPlayer` | `updateLOS` | `()` | 7.8% top-of-stack | Per-player LOS consumer on main thread. |
| 2.3 | `zombie.network.ServerLOS` | `updateLOS` | `(IsoPlayer)` | 10.7% inclusive | Main-thread half of the LOS thread handshake. |
| 2.4 | `zombie.characters.IsoPlayer` | `updateRemotePlayer` | `()` | 10.5% inclusive | Outer wrapper for remote-player update. Per-remote-player-per-tick cost. |
| 2.5 | `zombie.characters.IsoPlayer` | `TestZombieSpotPlayer` | varies | 3.0% inclusive | Zombie/player spot test. Scales with zombie count near players. |

**Already implemented:**
- `IsoAnimal.update()` — `AnimalUpdateMetrics` (outer wrapper; 2.1 would split out just the LOS piece)

### Tier 3 — Vehicles & networking

| # | Target class | Method | Args | JFR % (main) | What it measures |
|---|---|---|---|---|---|
| 3.1 | `zombie.vehicles.VehicleManager` | `serverUpdate` | `()` | 4.5% inclusive | One call per tick. Scales with player count. |
| 3.2 | `zombie.vehicles.VehicleManager` | `sendVehicles` | `(UdpConnection)` | inside 3.1 | Per-connection cost. Confirms whether per-player parallelization would help. |
| 3.3 | `zombie.vehicles.BaseVehicle` | `update` | `()` | 0.5% top-of-stack | Per-vehicle cost. Called many times per tick. |
| 3.4 | `zombie.network.GameServer` | `mainLoopDealWithNetData` | varies | indirect | Catches network-induced main-thread cost. Packet flood shows up here. |

### Tier 4 — Chunk lifecycle (load side)

| # | Target class | Method | Args | JFR % (main) | What it measures |
|---|---|---|---|---|---|
| 4.1 | `zombie.iso.IsoChunk` | `doLoadGridsquare` | varies | 2.9% inclusive | Chunk loads that stall main. Counterpart to chunk removal. |
| 4.2 | `zombie.iso.IsoChunk` | `Save` | `(boolean)` | 0.9% inclusive | Save calls that escape the SaveChunk thread onto main. |
| 4.3 | `zombie.network.ServerMap` | `postupdate` | `()` | 4.8% inclusive | Cell-list maintenance loop. Covers many small per-cell operations. |

### Tier 5 — Entity / Lua / animation systems

| # | Target class | Method | Args | JFR % (main) | What it measures |
|---|---|---|---|---|---|
| 5.1 | `zombie.entity.UsingPlayerUpdateSystem` | `update` | `()` | 2.1% top-of-stack | Entity-system tick for "in use" components. One call per tick. |
| 5.2 | `zombie.entity.GameEntityManager` | `Update` | `()` | 2.9% inclusive | Entity manager outer tick. |
| 5.3 | `zombie.popman.NetworkZombieManager` | `updateAuth` | `()` | 1.3% top-of-stack | Authoritative zombie sync. Scales with zombie count. |
| 5.4 | `zombie.popman.animal.AnimalSynchronizationManager` | `update` | `()` | small | Animal sync to clients. Pair with IsoAnimal metrics for send-side cost. |
| 5.5 | `se.krka.kahlua.vm.KahluaThread` | `luaMainloop` | varies | 0.7% top-of-stack | Lua VM time on main thread. Catches mod Lua cost. |

### Not worth instrumenting

These showed up in the JFR but are either leaf methods of already-instrumented parents, or run on off-main threads:

- `ArrayList.indexOfRange` / `HashMap.getNode` — leaves of instrumented methods; no new signal.
- `IsoGridSquare.CalcVisibility` / `LosUtil.lineClearCached` — `LOS` thread, not main.
- `WorldReuserThread.*`, `RecalcAllThread.*`, `SaveChunk*`, `LoadChunk*` — all off-main.

---

## Recommended implementation order

1. **Tier 1 (1.1, 1.2, 1.3, 1.4)** — validates the chunk-remove ArrayList→Set optimization.
2. **Tier 2.1 (`IsoAnimal.updateLOS`)** — splits out LOS cost so we can prove the LOS algorithm rewrite works.
3. **Tier 3.1 + 2.4 (`VehicleManager.serverUpdate` + `IsoPlayer.updateRemotePlayer`)** — clean per-tick numbers that scale with player count; baselines for future parallelism.
4. **Remaining tiers** — only if the first three don't account for the bulk of tick time.

## Notes

- Each new metrics class needs its `recordTick()` added to `MovingObjectUpdateSchedulerStartFrameAdvice.onEnter()`.
- All advice is server-only (guarded by `GameServer.server`).
- The metrics daemon threads are lightweight — one per metrics class, sleeping 60s between snapshots.
- Consider consolidating into a single `PerformanceMetrics` class with named counters if the number of metrics classes exceeds ~8, to avoid proliferating daemon threads.
