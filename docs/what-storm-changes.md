# What Storm Changes

Beyond loading mod jars, Storm rewrites a chunk of the game's bytecode at load
time to fix vanilla bugs, expose runtime tunables, dispatch events, and lift
hardcoded performance limits. Everything below is gated to the dedicated-server
JVM unless noted, and is always-on unless it points at a tunable flag in
[Server Configuration](server-configuration.md).

## Performance

- **Parallel server-LOS pipeline** — `ServerLOS` fans out across `1..16` worker threads (`Storm.ServerLosThreads` sandbox option); per-square LOS state, lighting arrays, and scratch vectors are made thread-safe and per-worker. Each worker owns a slot in the resized `LosUtil.cachedresults[]` so the vanilla per-square cache is reused instead of reallocated; concurrent `IsoRoom.onSee` dispatch is serialized through a single reentrant lock so room-discovery side effects stay deterministic.
- **Stride-based animal LOS** — `Storm.AnimalLOSTickInterval` distributes `IsoAnimal.updateLOS` across N ticks; `0` disables animal LOS entirely on servers where it isn't worth the cost.
- **O(1) cell-membership operations** — replaces vanilla's `ArrayList.contains()` / `remove(Object)` scans on the per-cell process queue, static-updater list, and removal list with sidecar hash indices. Dominates main-thread cost on chunk-unload bursts (see `CellAddToProcess*`, `IsoObjectStaticUpdaterRemoveSubst`, `CellProcessIsoObjectFlush`).
- **O(1) `ServerLOS.findData(IsoPlayer)`** — vanilla scans an `ArrayList<PlayerData>` by reference per call, dominating `IsoPlayer.updateLOS()` on busy servers. Storm caches the lookup in an identity-keyed sidecar (`ServerLOSPlayerDataCache`).
- **Configurable server tick rate** — `Storm.ServerFps` lifts the hardcoded 10 TPS cap and drives all three FPS controllers (main-loop tick gate, `PerformanceSettings.getLockFPS()` on the server, and the `IsoPhysicsObject.update()` FPS scalar) from a single value so physics stays in sync. `POST /storm/server/fps` retunes it live; the three controllers always move together (no per-controller HTTP endpoints).
- **Non-boxing `Stats.get(CharacterStat)`** — eliminates per-frame `Float` autoboxing on the hottest character-stat accessor.
- **Skip server-side electricity scan** — `IsoGenerator.update` stops iterating chunk tiles on the server (the result is only used by the client UI); chunk-position bookkeeping is used instead.

## Behavioral overrides

- **Packet rate limit disabled on the server** — `PacketsCache.isLimitExceeded(...)` is short-circuited, so the vanilla `ServerOptions.maxPacketsPerSecond` cap no longer applies to inbound or outbound traffic.
- **Zombie cull controls** — single `Storm.ZombieCullThreshold` sandbox option drives both the population target and the kill switch (`0` disables culling entirely, any positive value engages the Storm override and aims for `max(0, live - threshold)`); the threshold patch also fixes vanilla's over-cull bug that mass-deletes ~10% of the population per frame on overshoot.
- **UUID-based item transfers** — `StormTransferHandler` replaces vanilla's per-player byte-ID `Transaction` system for player ↔ player / bag / world / vehicle-part transfers; floor drops and dead-body containers still use vanilla. Eliminates ID-wraparound collisions and the "vacuous truth on ID 0" reject bug. Driven from `client/StormTransferFix.lua` over `sendClientCommand("StormTransfer", ...)`.
- **Faster natural-water collection** — `StormFastFillNaturalWater` overrides `ISTakeWaterAction:getDuration()` so filling from rivers, lakes, and other natural sources finishes in 100 ticks instead of vanilla's much-longer pull (taps and rain barrels are unaffected).
- **Mod command dispatch** — `CommandBase.findCommandCls` and `getSubClasses` consult `StormCommandRegistry`, so mod commands resolve in chat and show up in `/help` alongside vanilla ones.
- **Custom child-JVM bootstrap** — `CoopMaster` is rewritten to launch hosted servers through Storm's agent instead of a bare `ProcessBuilder`, so in-game-hosted servers also load Storm.
- **Protected uncaught exception handler** — the handler Storm installs at startup cannot be replaced by mods or vanilla code that calls `Thread.setDefaultUncaughtExceptionHandler`, so crashes always route through Storm's log writer.
- **Event hooks** — packet receipt (`OnPacketReceived`), ban actions (`onBanUser` / `onBanIp` / `onBanSteamID`), chat messages, Lua VM init, world-map render, item-transfer completion, and ~190 Lua event bridges become subscribable from Java via `@SubscribeEvent`. See [Mod Author Guide](mod-author-guide.md).

## Bug fixes shipped with Storm

Each line below is a vanilla bug Storm patches out, all default-on.

- **Cross-player action cancel** — `ActionManager.removeFromQueue` filtered by byte action id alone, so cancelling your own action also yanked every other player's queued action with the same id. Patched to filter by `(byteId, onlineId)`.
- **Cross-player transaction cancel** — same shape on `ItemTransactionPacket`: one player's cancel rejected every other player's pending transfers. Patched to filter by `(byteId, onlineId)`.
- **Stale transaction cascade** — `TransactionManager`'s consistency check rejected every new transfer after a container unloaded mid-flight; the patch sweeps stale transactions before the check.
- **Transaction byte-ID wraparound + ID-0 vacuous truth** — the vanilla `Transaction` allocator hands out 8-bit ids that wrap and collide across players, and `createItemTransaction` returns `0` on failure, which then matches every "no transaction" check downstream. Replaced by `StormTransferHandler`'s UUID-keyed protocol for player / bag / world / vehicle-part transfers.
- **Zombie id collisions ("zombie mitosis")** — vanilla's free-running 16-bit allocator wraps and reuses live ids on high-population servers; replaced with hash-probed allocation in `IsoObjectIDAllocate`.
- **Zombie map invariant** — `IsoZombie.update` re-establishes "`onlineId != -1` ⇒ in `ServerMap.zombieMap`" every tick, so chunk-load zombies stop falling out of authoritative state.
- **Pop-cell save append accumulation** — `IsoChunk.removeFromWorld` calls `ZombiePopulationManager.requestSaveCell` on every chunk unload, and the native `n_saveCell` appends each snapshot to `Saves/.../zpop/zpop_X_Y.bin` rather than overwriting it, so the same live zombie's 12-byte record accumulates dozens of copies in the same file as a player roams a pop cell. On the next reload, native emits every accumulated copy and N visually identical zombies spawn at the same square with sequential online ids — the cluster-of-clones behavior players colloquially call "zombie mitosis," distinct from the id-collision bug above. Storm short-circuits `requestSaveCell` on the dedicated server (`RequestSaveCellSuppressPatch`); the global autosave path that writes `zpop_virtual.bin` is independent and still persists live zombies on the normal cadence. **Caveat:** the patch stops new dupes from being written, but existing `zpop_X_Y.bin` files on disk still hold accumulated copies and will keep spawning clones on cell load until the on-disk files are cleaned (delete to regenerate from descriptors, or dedup in place).
- **Mid-handshake relevance leak (zombies at world origin)** — `UdpConnection`'s constructor initializes `releventPos[0] = new Vector3()` with default `relevantRange = 0`. Until the client's first `PlayerPacket` fires (typically 30+ s into the world-download handshake), the connection's stored "player position" is exactly `(0,0,0)`. `ServerMap.outsidePlayerInfluence` iterates every connection without filtering on `isFullyConnected()`, so the four ServerCells whose corners touch world `(0,0)` — `(0,0)`, `(0,-1)`, `(-1,0)`, `(-1,-1)` — claim relevance during every handshake. Cell `(-1,-1)` is suppressed by an unrelated guard; the other three stay in `loadedCells`, `LoadedAreas(serverCells=true)` reports them to the native pop manager, and virtual zombies materialize into the empty origin terrain. Storm short-circuits the four `UdpConnection` relevance methods (`isRelevantTo`, `RelevantToPlayerIndex`, `RelevantTo`, `getRelevantAndDistance`) to return "not relevant" whenever `isFullyConnected()` is false (`UdpConnectionRelevancePatch`), so handshaking connections stop forcing origin cells loaded. **Caveat:** the patch stops further accumulation, but zombies already materialized at origin remain — clean them up server-side with `cell:getZombieList()` + `:removeFromWorld()`/`:removeFromSquare()` filtered by radius.
- **`GeneralActionPacket.setReject()`** — populates `playerId` from the `UdpConnection` (vanilla leaves it `-1`, so reject responses never resolved an owner).
- **`NetTimedActionPacket` accept/reject** — serializes the server-side action instead of the inbound packet, so clients receive the correct state and duration on the response.
- **`AnimalInventoryItem` null on save** — `CompressIdenticalItems` filters null-animal entries before save, removing a frequent crash during world unload.
- **Null-`adef` animal NPEs** — guards on `IsoAnimal.update`, `canClimbStairs`, `reattachBackToMom`, `IsoMovingObject.isPushedByForSeparate`, and `BaseVehicle.save` for animals whose definition failed to resolve (broken mod packs, missing animal types).
- **Idempotent `SpriteConfig.onAddedToOwner()`** — skips re-init if already initialized, so sync packets stop re-resetting sprite state mid-game.
- **Case-insensitive whisper** — `ChatServer.processWhisperMessage` matches usernames case-insensitively and resolves to the canonical username (vanilla silently dropped whispers when capitalization differed).
- **Translator "Missing translation" spam** — `Translator.getText` short-circuits when the input doesn't look like a translation key, so already-translated UI strings stop emitting false-positive warnings.

## Mod-loader extensions

- **Mod jars on the classpath** — every `.jar` inside a mod's version-specific directory (e.g. `42/`) is loaded into `StormClassLoader` and scanned for a `ZomboidMod` entry point.
- **Lua auto-injection from jars** — any `lua/` directory inside a mod jar is registered into the game's Lua environment on `OnZomboidGlobalsLoad`, so a Storm mod ships its client/server/shared Lua next to its Java code in the same jar.
- **Annotation-driven HTTP / event / command surfaces** — mods register HTTP endpoints (`@HttpEndpoint`), event handlers (`@SubscribeEvent`), client-command handlers (`@OnClientCommand`), and server console commands (`StormCommandRegistry.MOD_COMMANDS`) without touching vanilla wiring.
- **`io.pzstorm.storm.halo.StormHalo`** — Java API to draw a temporary speech bubble over a player's head from server-side mod code (`setHalo(target, text)`, `setHalo(target, text, r, g, b)`, `setHaloFor(viewer, target, text)`). Backed by `client/StormHalo.lua` and PZ's native `IsoGameCharacter:addLineChatElement`, so bubbles render over remote players with no chat-log entry.
- **`PersistedBooleanConfigOption`** — subclass of vanilla `BooleanConfigOption` that auto-persists its value to `~/Zomboid/Storm/persisted-map-config-options/<name>.json` and reloads on construction. Drop-in replacement for mods that need a per-server toggle.
- **`StormUtils.getTextureResourceFromStream(name, classLoader)`** — load a PZ `Texture` straight from a jar resource without staging it on disk first.
- **`StormPrometheus.registry()`** — shared `PrometheusRegistry` for mod metrics; instruments registered here are scraped at `/metrics` alongside Storm's own (see [Prometheus Metrics](metrics.md) for naming conventions).
- **FMOD JNA bindings** — `io.pzstorm.storm.jna.fmod.FmodJNA` exposes the running game's FMOD system handle, 3D listener / channel attributes, cone settings, and custom roll-off curves via JNA, so mods can drive native audio without re-binding the library or shipping their own copy.
- **LuaLS type-stub dump** — every Java class exposed to Lua is written out as a LuaLS-compatible stub under `lua_stubs/` so IDEs can lint Lua against the live game API.

## Client-side patches

The client JVM ships a small historical set: a render-thread version overlay,
world-map filter dispatch, TIS-logo splash timing, and a Lua-event hook so mods
can listen for client-only events. **New client-side bytecode patches are not
added** — by policy, every new feature is redesigned to live in Lua, in a
server-side patch, or in `sendClientCommand` traffic. (Past client patches
caused compatibility breaks with non-Storm vanilla clients and accumulated
debugging risk on every game update.)

One experimental client-only patch is currently enabled:
`KahluaMetatableCachePatch` swaps a sentinel `KahluaTable` into
`KahluaThread.getClassMetatable`'s `cachedMetatables` map when the lookup
returns `null`, so the per-call `ArrayDeque`-allocating `computeIfAbsent`
lambda stops re-firing on every Kahlua op against the same class. Client JFR
profiling attributes ~85% of MainThread allocation pressure to this single
site. It is the only patch under `patch/client/experimental/` and the only
client-side performance patch shipped by Storm; see the class javadoc for
the full rationale.

## Client conveniences

The handful of client-side features Storm ships ride entirely on Lua and
`sendClientCommand` — no client bytecode patches involved.

- **Low-RAM detection popup** — `client/StormRamAllocation.lua` reads the client's `-Xmx` value on join, reports it to the server (populating `GET /storm/ram-allocations`), and pops up a "your game has <4 GiB allocated, consider `-Xmx8g --`" warning with a "don't show again" tickbox (persisted to `StormRamAllocationSettings.txt`).
- **Admin-requested screenshots** — `/screenshot <username>` (see [Built-in Server Commands](mod-author-guide.md#built-in-server-commands)) tells the named client to render a screenshot via `Core.takeScreenshot`, base64-chunk it, and stream it back to the server, which writes it to the player's Lua cache dir as `storm_screenshot_<user>_<id>.png`.
- **Bootstrap verification** — `client/StormBootstrapVerification.lua` and its server counterpart log a one-line confirmation that Storm is wired up on the matching JVM, so a misconfigured launcher is visible from the console without enabling debug logging.
- **Storm version overlay** — the main-menu render path stamps the running Storm version in the bottom-right of the title screen, so a user can confirm at a glance that the agent picked up the right jar.
