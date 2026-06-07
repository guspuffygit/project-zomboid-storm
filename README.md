# Storm Mod Loader

[![Maven Central](https://img.shields.io/maven-central/v/com.sentientsimulations/project-zomboid-storm)](https://central.sonatype.com/artifact/com.sentientsimulations/project-zomboid-storm)
[![License](https://img.shields.io/github/license/guspuffygit/project-zomboid-storm?logo=gnu)](https://www.gnu.org/licenses/)
[![Discord](https://img.shields.io/discord/823907021178798150?color=7289DA&label=discord&logo=discord&logoColor=white)](https://discord.gg/ZCmg9VsvSW)

Storm Mod Loader is a Java modding framework for Project Zomboid.

Successor to the original abandoned [Storm](https://github.com/pzstorm/storm)

## Installation

1. Subscribe to [Storm Mod Loader](https://steamcommunity.com/sharedfiles/filedetails/?id=3670772371) in the Steam Workshop.
2. Right click Project Zomboid in Steam Library, click Properties
3. In General, under Launch Options, copy and paste the line for your platform below into the input

#### Windows
```text
-agentpath:../../workshop/content/108600/3670772371/mods/storm/bootstrap/agentlib.dll=storm-bootstrap.jar --
```

#### Linux
```text
-javaagent:../../workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar --
```

#### Mac
```text
-javaagent:../../../../../workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar --
```

When you start the game, the main screen menu should show the Storm version in the right bottom of the screen.

## Local Install:

### Setup local.properties

1. Create a new file in the repo named `local.properties`
2. Specify these two required directories

* gameDir - Project Zomboid Installation directory
* zomboidDir - Project Zomboid configuration directory

```
gameDir=E:\\SteamLibrary\\steamapps\\common\\ProjectZomboid
zomboidDir=C:\\Users\\user\\Zomboid
```

### Deploy Storm Locally:

Windows:
```
.\gradlew.bat clean spotlessApply installBootstrap installStorm publishToMavenLocal
```

Linux / Mac:
```
./gradlew clean spotlessApply installBootstrap installStorm publishToMavenLocal
```

2. Right click Project Zomboid in Steam Library, click Properties
3. In General, under Launch Options, copy and paste the line below into the input

#### Windows
```text
-DstormType=local "-agentpath:C:\Users\<user>\Zomboid\Workshop\storm\Contents\mods\storm\bootstrap\agentlib.dll=storm-bootstrap.jar" --
```

#### Linux / Mac
```text
-javaagent:~/Zomboid/Workshop/storm/Contents/mods/storm/bootstrap/storm-bootstrap.jar -DstormType=local --
```

## Dedicated Server

Add `3670772371` to WorkshopItems in the server.ini file.

### Windows

The `StartServer64.bat` script does not pass extra arguments to the JVM, so you need to add the Storm flags directly to the `java.exe` command line in the bat file.

#### Workshop Install

Copy `StartServer64.bat` to `StartServer64-Storm.bat` and add the Storm flags before the `-cp` argument:

```bat
@setlocal enableextensions
@cd /d "%~dp0"

SET PZ_CLASSPATH=java/;java/projectzomboid.jar

".\jre64\bin\java.exe" ^
  -Djava.awt.headless=true ^
  -Dzomboid.steam=1 ^
  -Dzomboid.znetlog=1 ^
  -Dstorm.server=true ^
  -agentpath:./steamapps/workshop/content/108600/3670772371/mods/storm/bootstrap/agentlib.dll=storm-bootstrap.jar ^
  -XX:+UseZGC ^
  -XX:-CreateCoredumpOnCrash ^
  -XX:-OmitStackTraceInFastThrow ^
  -Xms16g ^
  -Xmx16g ^
  -Djava.library.path=natives/;natives/win64/;. ^
  -cp %PZ_CLASSPATH% ^
  zombie.network.GameServer ^
  -statistic 0 ^
  -servername yourserver ^
  %1 %2

PAUSE
```

#### Local Install

Add these flags to the java arguments:
```bat
-DstormType=local
-DLOG_LEVEL=DEBUG
```

### Linux

### Workshop Install

```bash
./start-server.sh \
  -javaagent:./steamapps/workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar \
  -Dstorm.server=true \
  -- \
  -servername yourserver
```

### Local Install

```bash
./start-server.sh \
  -javaagent:~/Zomboid/Workshop/storm/Contents/mods/storm/bootstrap/storm-bootstrap.jar \
  -Dstorm.server=true \
  -DstormType=local \
  -DLOG_LEVEL=DEBUG \
  -- \
  -servername yourserver
```

## What Storm Changes

Beyond loading mod jars, Storm rewrites a chunk of the game's bytecode at load
time to fix vanilla bugs, expose runtime tunables, dispatch events, and lift
hardcoded performance limits. Everything below is gated to the dedicated-server
JVM unless noted, and is always-on unless it points at a tunable flag in the
[Server Configuration](#server-configuration) section.

### Performance

- **Parallel server-LOS pipeline** — `ServerLOS` fans out across `1..16` worker threads (`-Dstorm.serverLos.threads`); per-square LOS state, lighting arrays, and scratch vectors are made thread-safe and per-worker. Each worker owns a slot in the resized `LosUtil.cachedresults[]` so the vanilla per-square cache is reused instead of reallocated; concurrent `IsoRoom.onSee` dispatch is serialized through a single reentrant lock so room-discovery side effects stay deterministic.
- **Stride-based animal LOS** — `-Dstorm.animalLOS.tickInterval` distributes `IsoAnimal.updateLOS` across N ticks; `0` disables animal LOS entirely on servers where it isn't worth the cost.
- **O(1) cell-membership operations** — replaces vanilla's `ArrayList.contains()` / `remove(Object)` scans on the per-cell process queue, static-updater list, and removal list with sidecar hash indices. Dominates main-thread cost on chunk-unload bursts (see `CellAddToProcess*`, `IsoObjectStaticUpdaterRemoveSubst`, `CellProcessIsoObjectFlush`).
- **O(1) `ServerLOS.findData(IsoPlayer)`** — vanilla scans an `ArrayList<PlayerData>` by reference per call, dominating `IsoPlayer.updateLOS()` on busy servers. Storm caches the lookup in an identity-keyed sidecar (`ServerLOSPlayerDataCache`).
- **Configurable server tick rate** — `-Dstorm.server.fps` / `-Dstorm.server.tickIntervalMs` lift the hardcoded 10 TPS cap; `lockFps` and `IsoPhysicsObject` server fps are linked so physics stays in sync.
- **Non-boxing `Stats.get(CharacterStat)`** — eliminates per-frame `Float` autoboxing on the hottest character-stat accessor.
- **Skip server-side electricity scan** — `IsoGenerator.update` stops iterating chunk tiles on the server (the result is only used by the client UI); chunk-position bookkeeping is used instead.

### Behavioral overrides

- **Packet rate limit disabled on the server** — `PacketsCache.isLimitExceeded(...)` is short-circuited, so the vanilla `ServerOptions.maxPacketsPerSecond` cap no longer applies to inbound or outbound traffic.
- **Zombie cull controls** — opt-in toggle (`-Dstorm.disableZombieCull`) and population-target override (`-Dstorm.zombieCullThreshold`); the threshold patch also fixes vanilla's over-cull bug that mass-deletes ~10% of the population per frame on overshoot.
- **UUID-based item transfers** — `StormTransferHandler` replaces vanilla's per-player byte-ID `Transaction` system for player ↔ player / bag / world / vehicle-part transfers; floor drops and dead-body containers still use vanilla. Eliminates ID-wraparound collisions and the "vacuous truth on ID 0" reject bug. Driven from `client/StormTransferFix.lua` over `sendClientCommand("StormTransfer", ...)`.
- **Faster natural-water collection** — `StormFastFillNaturalWater` overrides `ISTakeWaterAction:getDuration()` so filling from rivers, lakes, and other natural sources finishes in 100 ticks instead of vanilla's much-longer pull (taps and rain barrels are unaffected).
- **Mod command dispatch** — `CommandBase.findCommandCls` and `getSubClasses` consult `StormCommandRegistry`, so mod commands resolve in chat and show up in `/help` alongside vanilla ones.
- **Custom child-JVM bootstrap** — `CoopMaster` is rewritten to launch hosted servers through Storm's agent instead of a bare `ProcessBuilder`, so in-game-hosted servers also load Storm.
- **Protected uncaught exception handler** — the handler Storm installs at startup cannot be replaced by mods or vanilla code that calls `Thread.setDefaultUncaughtExceptionHandler`, so crashes always route through Storm's log writer.
- **Event hooks** — packet receipt (`OnPacketReceived`), ban actions (`onBanUser` / `onBanIp` / `onBanSteamID`), chat messages, Lua VM init, world-map render, item-transfer completion, and ~190 Lua event bridges become subscribable from Java via `@SubscribeEvent`. See [Mod Author API](#mod-author-api).

### Bug fixes shipped with Storm

Each line below is a vanilla bug Storm patches out, all default-on.

- **Cross-player action cancel** — `ActionManager.removeFromQueue` filtered by byte action id alone, so cancelling your own action also yanked every other player's queued action with the same id. Patched to filter by `(byteId, onlineId)`.
- **Cross-player transaction cancel** — same shape on `ItemTransactionPacket`: one player's cancel rejected every other player's pending transfers. Patched to filter by `(byteId, onlineId)`.
- **Stale transaction cascade** — `TransactionManager`'s consistency check rejected every new transfer after a container unloaded mid-flight; the patch sweeps stale transactions before the check.
- **Transaction byte-ID wraparound + ID-0 vacuous truth** — the vanilla `Transaction` allocator hands out 8-bit ids that wrap and collide across players, and `createItemTransaction` returns `0` on failure, which then matches every "no transaction" check downstream. Replaced by `StormTransferHandler`'s UUID-keyed protocol for player / bag / world / vehicle-part transfers.
- **Zombie id collisions ("zombie mitosis")** — vanilla's free-running 16-bit allocator wraps and reuses live ids on high-population servers; replaced with hash-probed allocation in `IsoObjectIDAllocate`.
- **Zombie map invariant** — `IsoZombie.update` re-establishes "`onlineId != -1` ⇒ in `ServerMap.zombieMap`" every tick, so chunk-load zombies stop falling out of authoritative state.
- **`GeneralActionPacket.setReject()`** — populates `playerId` from the `UdpConnection` (vanilla leaves it `-1`, so reject responses never resolved an owner).
- **`NetTimedActionPacket` accept/reject** — serializes the server-side action instead of the inbound packet, so clients receive the correct state and duration on the response.
- **`AnimalInventoryItem` null on save** — `CompressIdenticalItems` filters null-animal entries before save, removing a frequent crash during world unload.
- **Null-`adef` animal NPEs** — guards on `IsoAnimal.update`, `canClimbStairs`, `reattachBackToMom`, `IsoMovingObject.isPushedByForSeparate`, and `BaseVehicle.save` for animals whose definition failed to resolve (broken mod packs, missing animal types).
- **Idempotent `SpriteConfig.onAddedToOwner()`** — skips re-init if already initialized, so sync packets stop re-resetting sprite state mid-game.
- **Case-insensitive whisper** — `ChatServer.processWhisperMessage` matches usernames case-insensitively and resolves to the canonical username (vanilla silently dropped whispers when capitalization differed).
- **Translator "Missing translation" spam** — `Translator.getText` short-circuits when the input doesn't look like a translation key, so already-translated UI strings stop emitting false-positive warnings.

### Mod-loader extensions

- **Mod jars on the classpath** — every `.jar` inside a mod's version-specific directory (e.g. `42/`) is loaded into `StormClassLoader` and scanned for a `ZomboidMod` entry point.
- **Lua auto-injection from jars** — any `lua/` directory inside a mod jar is registered into the game's Lua environment on `OnZomboidGlobalsLoad`, so a Storm mod ships its client/server/shared Lua next to its Java code in the same jar.
- **Annotation-driven HTTP / event / command surfaces** — mods register HTTP endpoints (`@HttpEndpoint`), event handlers (`@SubscribeEvent`), client-command handlers (`@OnClientCommand`), and server console commands (`StormCommandRegistry.MOD_COMMANDS`) without touching vanilla wiring.
- **`io.pzstorm.storm.halo.StormHalo`** — Java API to draw a temporary speech bubble over a player's head from server-side mod code (`setHalo(target, text)`, `setHalo(target, text, r, g, b)`, `setHaloFor(viewer, target, text)`). Backed by `client/StormHalo.lua` and PZ's native `IsoGameCharacter:addLineChatElement`, so bubbles render over remote players with no chat-log entry.
- **`PersistedBooleanConfigOption`** — subclass of vanilla `BooleanConfigOption` that auto-persists its value to `~/Zomboid/Storm/persisted-map-config-options/<name>.json` and reloads on construction. Drop-in replacement for mods that need a per-server toggle.
- **`StormUtils.getTextureResourceFromStream(name, classLoader)`** — load a PZ `Texture` straight from a jar resource without staging it on disk first.
- **`StormPrometheus.registry()`** — shared `PrometheusRegistry` for mod metrics; instruments registered here are scraped at `/metrics` alongside Storm's own (see [Prometheus Metrics](#prometheus-metrics) for naming conventions).
- **FMOD JNA bindings** — `io.pzstorm.storm.jna.fmod.FmodJNA` exposes the running game's FMOD system handle, 3D listener / channel attributes, cone settings, and custom roll-off curves via JNA, so mods can drive native audio without re-binding the library or shipping their own copy.
- **LuaLS type-stub dump** — every Java class exposed to Lua is written out as a LuaLS-compatible stub under `lua_stubs/` so IDEs can lint Lua against the live game API.

### Client-side patches

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

### Client conveniences

The handful of client-side features Storm ships ride entirely on Lua and
`sendClientCommand` — no client bytecode patches involved.

- **Low-RAM detection popup** — `client/StormRamAllocation.lua` reads the client's `-Xmx` value on join, reports it to the server (populating `GET /storm/ram-allocations`), and pops up a "your game has <4 GiB allocated, consider `-Xmx8g --`" warning with a "don't show again" tickbox (persisted to `StormRamAllocationSettings.txt`).
- **Admin-requested screenshots** — `/screenshot <username>` (see [Built-in Server Commands](#built-in-server-commands)) tells the named client to render a screenshot via `Core.takeScreenshot`, base64-chunk it, and stream it back to the server, which writes it to the player's Lua cache dir as `storm_screenshot_<user>_<id>.png`.
- **Bootstrap verification** — `client/StormBootstrapVerification.lua` and its server counterpart log a one-line confirmation that Storm is wired up on the matching JVM, so a misconfigured launcher is visible from the console without enabling debug logging.
- **Storm version overlay** — the main-menu render path stamps the running Storm version in the bottom-right of the title screen, so a user can confirm at a glance that the agent picked up the right jar.

## Server Configuration

### Available system properties

Storm reads these at startup. Pass as `-D<key>=<value>` on the JVM command line (or via
`JAVA_TOOL_OPTIONS` in a launcher script). All flags are opt-in unless noted.

| Flag | Purpose |
|------|---------|
| `-Dstorm.server=true` | **Required on the dedicated server.** Tells the bootstrap agent to target `GameServer` instead of `MainScreenState`. |
| `-DstormType=local` | Load Storm from `~/Zomboid/Workshop/storm` instead of the Steam workshop path. Local development only. |
| `-DLOG_LEVEL=DEBUG` | Storm log verbosity (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`). Default `INFO`. |
| `-Dstorm.http.port=<port>` | Start Storm's HTTP server on `<port>`. Required for every HTTP endpoint (hot-reload + runtime tuning). Conventionally `41798` on the dedicated server and `8089` on the client. |
| `-Dstorm.hotreload=true` | Register the `/reload` and `/eval` developer endpoints. See [Developer Hot-Reload Endpoints](#developer-hot-reload-endpoints). **Local development only.** |
| `-Dstorm.hotreload.eval.classes=<dir>` | Directory holding the compiled `EvalScript.class` (required by `/eval`). |
| `-Dstorm.hotreload.eval.source=<dir>` | Optional. Directory holding `EvalScript.java`; enables a staleness guard. |
| `-Dstorm.serverLos.threads=<n>` | Parallel server-LOS worker count. Range `1..16`. Default `1` (single-threaded, byte-identical visibility to vanilla). Increase on busy servers — typical production value `4..12`. Live-tunable via `POST /storm/serverLos/threads`. |
| `-Dstorm.animalLOS.tickInterval=<n>` | Run each animal's LOS scan once every `n` server ticks. Range `0..64`. Default `1` (vanilla — every tick). `0` disables animal LOS entirely. Live-tunable via `POST /storm/animalLOS/tickInterval`. |
| `-Dstorm.disableZombieCull=true` | Disable the vanilla zombie cull/despawn pass. Default off. Live-tunable via `POST /storm/server/zombieCull/disabled`. |
| `-Dstorm.zombieCullThreshold=<n>` | Raise the target live-zombie population above vanilla's hard 500 cap on the `ZombieCountOptimiser` sandbox option — the cull aims for `max(0, live - n)`. Also fixes vanilla's over-cull bug so the count converges to `n` instead of being mass-deleted ~10% per frame on overshoot. Default `-1` (no override; vanilla runs untouched). Live-tunable via `POST /storm/server/zombieCull/threshold`. |
| `-Dstorm.server.fps=<fps>` | Unified server-FPS knob — sets `tickIntervalMs`, `lockFps`, and `isoPhysics.serverFps` together. Range `1..240`. Per-knob overrides below win when both are set. |
| `-Dstorm.server.tickIntervalMs=<ms>` | Server tick interval. Vanilla `100` (= 10 TPS). Range `0..1000`; `0` removes throttling. Live-tunable via `POST /storm/server/tickInterval`. |
| `-Dstorm.server.lockFps=<fps>` | Value reported by `PerformanceSettings.getLockFPS()` on the server. Default `10`, range `1..240`. Live-tunable. |
| `-Dstorm.isoPhysics.serverFps=<fps>` | FPS divisor used inside `IsoPhysicsObject.update()` on the server. Default `10`. Live-tunable. |
| `-DprometheusPort=<port>` | Start PZ's built-in Prometheus HTTP server on `<port>`. Required to scrape Storm + `pz_*` + `jvm_*` metrics at `/metrics`. (PZ flag — Storm registers into PZ's default registry.) |
| `-DprometheusHost=<host>` | Hostname/IP the server reports for itself in metrics endpoints. Defaults to `GameServer.ip`. (PZ flag.) |

### Production launcher example (Linux)

A real-world dedicated-server launcher. Uses `JAVA_TOOL_OPTIONS` so the Storm agent
and flags apply to every `java` invocation — whether the server starts via the
`ProjectZomboid64` wrapper or by calling `java` directly.

```bash
#!/bin/bash
ulimit -c unlimited
ulimit -n 65535

INSTDIR="`dirname $0`" ; cd "${INSTDIR}" ; INSTDIR="`pwd`"
TIMESTAMP=$(date '+%Y%m%d_%H%M%S')

# Storm + your other Storm-based mod workshop ids (3670772371 = Storm itself)
WORKSHOP_IDS=(
    3670772371
    # ...your other Storm mod workshop ids here...
)

WORKSHOP_ARGS=""
for id in "${WORKSHOP_IDS[@]}"; do
    WORKSHOP_ARGS+=" +workshop_download_item 108600 $id validate"
done

# Refresh workshop mods before launching so Storm + mods are up to date
steamcmd +force_install_dir "$PWD" +login anonymous $WORKSHOP_ARGS +quit

export PATH="${INSTDIR}/jre64/bin:$PATH"
export LD_LIBRARY_PATH="${INSTDIR}/linux64:${INSTDIR}/natives:${INSTDIR}:${INSTDIR}/jre64/lib/amd64:${LD_LIBRARY_PATH}"
JSIG="${INSTDIR}/jre64/lib/libjsig.so"

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} \
    -javaagent:${INSTDIR}/steamapps/workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar \
    -Dstorm.server=true \
    -DLOG_LEVEL=debug \
    -Dstorm.http.port=41798 \
    -Dstorm.serverLos.threads=12 \
    -Dstorm.animalLOS.tickInterval=8 \
    -Dstorm.disableZombieCull=true \
    -Dstorm.hotreload=true \
    -Dstorm.hotreload.eval.classes=/home/pzuser/lua-scripts/eval-scripts \
    -DprometheusPort=9092 \
    -DprometheusHost=<your-host>"

LD_PRELOAD="${LD_PRELOAD}:${JSIG}" ./ProjectZomboid64 "$@" \
    > >(tee "${INSTDIR}/crash-logs/stdout_${TIMESTAMP}.log") \
    2> >(tee "${INSTDIR}/crash-logs/stderr_${TIMESTAMP}.log" >&2)
```

Production notes:
- `ulimit -c unlimited` plus `LD_PRELOAD=libjsig.so` is required for the JVM to
  produce usable core dumps on crash — `libjsig` cooperates with the JVM's signal
  chaining so the kernel's core-dump handler actually fires.
- `JAVA_TOOL_OPTIONS` is read by every Java invocation, so the Storm agent and
  flags apply whether the server is launched via the `ProjectZomboid64` wrapper
  or by calling `java` directly.
- The `steamcmd ... +workshop_download_item` preinstall step ensures Storm and
  every Storm-based mod is current before launch — Storm's class transformers run
  at load time and need the deployed jars in place.
- The `crash-logs/` directory captures timestamped stdout/stderr so a crash
  postmortem has both the JVM core dump and the surrounding console output.

## Runtime Tuning HTTP API

When Storm's HTTP server is enabled (`-Dstorm.http.port=<port>`), the following
endpoints are always available. They let an operator inspect and change tuning
knobs on a live server without restarting — every `POST` returns the requested
value alongside the value that was actually applied after clamping.

All endpoints return JSON unless noted; `GET` reads the current value, `POST`
writes the new one. Query parameters are passed in the URL (no request body).

| Method | Path | Query | Purpose |
|--------|------|-------|---------|
| GET | `/health` | — | Liveness probe. Always returns `200 OK`. |
| GET | `/storm/version` | — | Plain-text Storm version string. |
| GET / POST | `/storm/server/tickInterval` | `ms=<n>` | Server tick interval in milliseconds. Backs `-Dstorm.server.tickIntervalMs`. |
| GET / POST | `/storm/server/lockFps` | `fps=<n>` | Value reported by `PerformanceSettings.getLockFPS()`. Backs `-Dstorm.server.lockFps`. |
| GET / POST | `/storm/server/fps` | `fps=<n>` | Unified server-fps knob — sets tickInterval, lockFps, and isoPhysics fps together. Backs `-Dstorm.server.fps`. |
| GET / POST | `/storm/isoPhysics/serverFps` | `fps=<n>` | `IsoPhysicsObject.update()` server fps divisor. Backs `-Dstorm.isoPhysics.serverFps`. |
| GET / POST | `/storm/serverLos/threads` | `n=<n>` | Parallel server-LOS worker thread count (`1..16`). Backs `-Dstorm.serverLos.threads`. |
| GET / POST | `/storm/animalLOS/tickInterval` | `ticks=<n>` | Animal-LOS scan period in ticks (`0..64`; `0` disables). Backs `-Dstorm.animalLOS.tickInterval`. |
| GET / POST | `/storm/server/zombieCull/disabled` | `disabled=true\|false` | Toggle the vanilla zombie cull/despawn pass. Backs `-Dstorm.disableZombieCull`. |
| GET / POST | `/storm/server/zombieCull/threshold` | `n=<n>` | Target live-zombie population for the cull (raises vanilla's 500 cap); `-1` reverts to vanilla. Backs `-Dstorm.zombieCullThreshold`. |
| GET | `/storm/server/players` | — | Currently-connected players (`username`, `steamId`, `ip`). |
| GET | `/storm/ram-allocations` | — | Per-player JVM RAM allocations reported by connected clients. |

Mod authors can register additional endpoints by annotating a handler method
with `@HttpEndpoint(path = "...", method = "GET"|"POST")` on any class discovered
by `StormEventDispatcher`. The dispatcher rejects handlers with the wrong return
type or signature at registration time and serves them on a shared thread pool.

```bash
# Read current tick interval (10 TPS = 100 ms)
curl http://localhost:41798/storm/server/tickInterval
# -> {"tickIntervalMs":100,"tps":10.00}

# Bump the unified server fps to 20 (50 ms tick, lockFps 20, physicsFps 20)
curl -X POST 'http://localhost:41798/storm/server/fps?fps=20'
# -> {"requestedFps":20,"appliedFps":20,"tickIntervalMs":50,"tps":20.00,"lockFps":20,"physicsFps":20}

# Disable the zombie cull pass entirely
curl -X POST 'http://localhost:41798/storm/server/zombieCull/disabled?disabled=true'
# -> {"requested":true,"applied":true}
```

## Prometheus Metrics

Storm piggybacks on Project Zomboid's built-in Prometheus integration. Setting
`-DprometheusPort=<port>` starts PZ's HTTP server on that port and exposes
`/metrics`, where Storm's instrumentation appears alongside `pz_*` and `jvm_*`.

Storm registers ~36 instruments covering the dedicated server's hot paths:

- **Server tick** — `storm_server_tick_total`, `storm_server_tick_duration_seconds` (the real TPS signal; PZ's `performance{parameter=fps}` is mislabeled cycle-ms).
- **Line-of-sight** — `storm_server_los_*` (player + cell pipeline) and `storm_animal_los_*` (animal scans).
- **World** — chunk load / save / remove, cell add-to-process / unload, `IsoObject.removeFromWorld`, `ServerMap.postUpdate`.
- **Entities** — animal update / sync, vehicle server update / send, remote + using-player updates, entity manager update, zombie spot-player, network zombie auth, `GameTime` step.
- **Lua + networking** — `storm_lua_mainloop_*`, packet dispatch, event dispatch, HTTP endpoint latency, item transfer events.
- **JVM diagnostics** — per-thread allocation bytes, BitHeader pool churn (byte/short/int/long), IsoObject id-pool occupancy, NetData buffer stats.

Mods declare instruments the same way Storm does — `private static final` fields
registered into `StormPrometheus.registry()` (see CLAUDE.md for a worked example).
Use snake_case names prefixed by component (`mymod_*`), `_total` for counters,
seconds / bytes as base units, and `.nativeOnly()` for latency histograms so
buckets grow dynamically.

```bash
# Scrape Storm + PZ + JVM metrics
curl http://localhost:9092/metrics | grep ^storm_
```

## Built-in Server Commands

Storm registers a small set of debug commands in addition to vanilla's. They are
gated to `Capability.DebugConsole` and dispatched through PZ's normal command
pipeline, so they work from the server console, RCON, or any admin client.

| Command | Usage | Purpose |
|---------|-------|---------|
| `ping` | `/ping` | Responds with `pong`. Health-check the command pipeline end-to-end. |
| `printdebug` | `/printdebug events` \| `/printdebug sounds` | Dump the recorded triggered-event log or the `GameSounds` registry to `~/Zomboid/Logs/<date>_DebugLog-server.txt`. |
| `screenshot` | `/screenshot <username>` | Ask a connected client to render a screenshot and write it to its Lua cache dir as `storm_screenshot_<user>_<id>.png`. |

Mods register their own commands by adding a `CommandBase` subclass to
`StormCommandRegistry.MOD_COMMANDS`; Storm patches `CommandBase.findCommandCls`
and `CommandBase.getSubClasses` so mod commands resolve and show up in `/help`
alongside vanilla ones.

## Lua API

Storm exposes a small `Storm` table plus a handful of utility libraries to the
game's Lua environment on both client and server. Everything is safe to call
from any Lua script after `OnZomboidGlobalsLoad`.

| Symbol | Purpose |
|--------|---------|
| `Storm.isEnabled()` | `true` when Storm is loaded — handy as a feature-detect guard for vanilla-compatible mods. |
| `Storm.getVersion()` | Storm version string (matches `GET /storm/version`). |
| `Storm.debug(...)` | Forwards its arguments to Storm's debug logger. |
| `PersistedTable:save(file, tbl)` / `PersistedTable:read(file)` | Persist a flat `key=value` Lua table to a Zomboid-managed text file and read it back. Skips function / table values. Useful for client-side preference toggles. |
| `StormBase64.encode(bytes [, start, end])` / `StormBase64.decode(str)` | Pure-Lua base64 codec used by Storm's screenshot pipeline; mods can reuse it for binary `sendClientCommand` payloads since vanilla Lua can't carry raw bytes through the network table. |

Any Lua files placed under `lua/` inside a mod jar are automatically loaded into
the Lua environment on `OnZomboidGlobalsLoad`, so a Storm mod can ship its
client / server / shared scripts inside the same jar as its Java code.

## Mod Author API

A Storm mod is a regular `.jar` that ships at least one class implementing
`io.pzstorm.storm.mod.ZomboidMod`. Storm scans every jar in a mod's
version-specific directory (e.g. `42/`), instantiates the entry point, and gives
it three hooks for registering surfaces:

```java
public final class MyMod implements ZomboidMod {

    @Override
    public void registerEventHandlers() {
        StormEventDispatcher.registerEventHandler(MyHandlers.class);   // static handlers
        StormEventDispatcher.registerEventHandler(new MyOtherHandler()); // instance handlers
    }

    @Override
    public List<Class<?>> getCommandClasses() {
        return List.of(MyServerCommand.class); // CommandBase subclasses
    }

    @Override
    public List<StormClassTransformer> getClassTransformers() {
        // Server-only bytecode patches. Gate on StormEnv.isStormServer() —
        // GameServer.server is false at collectTransformers() time and will
        // silently drop every transformer. Client-side patches are not allowed.
        if (!StormEnv.isStormServer()) {
            return List.of();
        }
        return List.of(new MyServerPatch());
    }
}
```

Logging conventions: `io.pzstorm.storm.logging.StormLogger.LOGGER` is the
preconfigured SLF4J logger every Storm component uses; mods are expected to
either grab it directly or attach their own `LoggerFactory.getLogger(...)`. Log
output is routed to `~/Zomboid/Logs/storm/main.log` and `storm/debug.log` per
the `-DLOG_LEVEL` flag.

### Annotation surfaces

Every handler class registered with `StormEventDispatcher` is scanned for the
following annotations. There is no per-method registration call — drop the
annotation on a method, register the class, and Storm wires it up.

| Annotation | Method signature | Fires when |
|------------|------------------|-------------|
| `@SubscribeEvent` | `(SomeZomboidEvent)` — exactly one `ZomboidEvent` parameter | The matching event is dispatched. Used for both Java-side events (`OnPacketReceivedEvent`, `OnLuaManagerInitEvent`, `OnBanUserEvent`, `OnItemTransferCompletedEvent`, `OnMainScreenRenderEvent`, …) and the ~190 Lua event bridges (`OnGameStartEvent`, `OnFillInventoryObjectContextMenuEvent`, etc.). |
| `@OnPacketReceived("PacketSimpleName")` | `(OnPacketReceivedEvent)` | A patched server packet's `processServer` runs. Filters by simple class name so handlers only fire for the packet they care about. Read protected fields via `event.getField("name")` (cached). |
| `@OnClientCommand` + a `ClientCommandEvent` subclass annotated `@ClientCommand(module="X", command="Y")` | `(MyTypedClientCommandEvent)` — single typed event parameter | The server receives `sendClientCommand("X", "Y", args)`. The event subclass extends `StormKahluaTable`, so handlers read args directly off `event.rawget("key")` instead of `KahluaTable` plumbing. |
| `@HttpEndpoint(path = "...", method = "GET" \| "POST")` | `(HttpRequestEvent)` or `(HttpRequestEvent, BodyT)` returning `void` | Storm's HTTP server receives the request. Paths are exact-match. With a body parameter, the dispatcher Jackson-decodes the body before invoking the handler and rejects malformed bodies with `400`. Requires `-Dstorm.http.port=<port>`. |

Handlers may be static (register the class) or instance (register an
instance) — Storm rejects a mix on the same handler.

### Typed packet events

In addition to the raw `OnPacketReceivedEvent`, every patched packet has a
typed subclass under `io.pzstorm.storm.event.packet.*` (e.g.
`ItemTransactionPacketEvent`, `EquipPacketEvent`, `NetTimedActionPacketEvent`,
`HitCharacterEvent`, `PlaySoundPacketEvent`, ~125 in total). Subscribe with
`@SubscribeEvent` on the typed class to get a strongly-typed `getPacket()`,
field-cache helpers, and a `capturePreState()` hook for snapshotting state
before the packet's `processServer` mutates it:

```java
@SubscribeEvent
public static void onTransfer(ItemTransactionPacketEvent event) {
    ItemTransactionPacket pkt = event.getPacket(); // typed, no reflection
    // Most packet fields are still protected; reach for them via
    //   event.getField("fieldName")  — cached after first read.
    Object state = event.getField("state");
}
```

### Storm Lua hooks for mod authors

| Lua hook | Usage |
|----------|-------|
| `Storm.isEnabled()` / `Storm.getVersion()` / `Storm.debug(msg)` | Cheap feature-detect / version probe / debug-log shortcut from any Lua script. Vanilla-compatible mods can guard a fallback path on `Storm and Storm.isEnabled()`. |
| Auto-injected `lua/` from a mod jar | Place client / server / shared scripts under `lua/` in the jar and Storm registers them into PZ's loader on `OnZomboidGlobalsLoad`. |
| `PersistedTable` / `StormBase64` | See [Lua API](#lua-api). Use `StormBase64` to chunk binary payloads through `sendServerCommand` / `sendClientCommand`, which can't carry raw bytes. |

## Developer Hot-Reload Endpoints

Storm ships two optional HTTP endpoints for iterating on a running game without restarting it:

- `POST /reload` — compiles and runs a Lua snippet in the live `LuaManager` environment.
- `GET /eval` — loads and runs a freshly compiled `EvalScript` Java class.

They are **off by default** and intended for local development only.

> ⚠️ Both endpoints execute arbitrary code in the game JVM. Only enable them on a trusted local
> machine — never on a public-facing client or server.

### Enabling

The endpoints ride on Storm's built-in HTTP server, so you need **both** of the first two flags below.
`/eval` additionally needs the classes directory.

| Flag | Purpose |
|------|---------|
| `-Dstorm.http.port=<port>` | Starts Storm's HTTP server on `<port>` (required for any endpoint). |
| `-Dstorm.hotreload=true` | Registers `/reload` and `/eval`. Without it they return `404`. |
| `-Dstorm.hotreload.eval.classes=<dir>` | Directory holding the compiled `EvalScript.class`. Required by `/eval`. |
| `-Dstorm.hotreload.eval.source=<dir>` | Optional. Directory holding `EvalScript.java`; enables a staleness guard that fails fast when the source is newer than the compiled class. |

Example (Linux dedicated server, local install):

```bash
./start-server.sh \
  -javaagent:~/Zomboid/Workshop/storm/Contents/mods/storm/bootstrap/storm-bootstrap.jar \
  -Dstorm.server=true \
  -DstormType=local \
  -Dstorm.http.port=41798 \
  -Dstorm.hotreload=true \
  -Dstorm.hotreload.eval.classes=/path/to/eval-classes \
  -- \
  -servername yourserver
```

On the client, add the same `-Dstorm.http.port` / `-Dstorm.hotreload` flags to the game's launch
options. The client and server each run their own HTTP server, so give them different ports
(e.g. `8089` for the client, `41798` for the server).

### `POST /reload` — Lua

Send the Lua source as the **raw request body**. It is compiled with Kahlua and run in the game's
`LuaManager.env`; the chunk's return value comes back in the response. On the client the snippet runs
on the game's `MainThread`, so `getPlayer()`, `getCell()`, the UI, etc. are all available.

```bash
curl -X POST --data-binary 'return "pong"' http://localhost:41798/reload
# -> OK: pong
```

Responses:

- `OK` — chunk ran, no return value.
- `OK: <value>` — chunk returned a value.
- `ERROR: <message>` — compilation or execution failed (HTTP `200`, body carries the Lua error).
- `400` — empty request body.

### `GET /eval` — Java

Write an `EvalScript.java` in the **default package** (no `package` line) with a
`public static Object run()` method, compile it into the directory named by
`-Dstorm.hotreload.eval.classes`, then call the endpoint. A fresh classloader is created on every
request, so each call picks up the latest recompiled class. The script has full access to `zombie.*`
and `io.pzstorm.*`.

```java
// EvalScript.java  (default package — no package declaration)
public class EvalScript {
    public static Object run() {
        return "server=" + zombie.network.GameServer.server;
    }
}
```

```bash
# Compile against projectzomboid.jar + the Storm jar, output into the configured classes dir:
javac -cp '<projectzomboid.jar>:<storm.jar>' -d /path/to/eval-classes EvalScript.java
curl http://localhost:41798/eval
# -> server=true
```

The endpoint returns `String.valueOf(run())`, or an `ERROR:` line with a stack trace if the class is
missing, stale, or `run()` throws.

### Client vs server

Both endpoints work on the client and the dedicated server. On the server they run directly on the
HTTP thread; on the client the work is dispatched to the game's `MainThread` so it never touches Lua
or rendering state off-thread.
