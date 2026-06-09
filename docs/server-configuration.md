# Server Configuration

Storm's server-tunable knobs split into two groups:

- **Bootstrap / dev flags** are still set as `-D<key>=<value>` on the JVM command line — they have
  to take effect before the server reaches `OnServerStarted` (bootstrap target, log level, HTTP
  port, hot-reload, Prometheus).
- **Performance knobs** moved off `-D` flags and onto vanilla **sandbox options** — admins set them
  in the world's `<SaveName>.ini` (or the in-game world setup UI on the "Storm | Performance" tab),
  and Storm reads them once at `OnServerStarted` and pushes the values through the live setters.
  Runtime tuning still happens over the HTTP endpoints in [HTTP API](http-api.md).

## Bootstrap / dev system properties

Pass as `-D<key>=<value>` on the JVM command line (or via `JAVA_TOOL_OPTIONS` in a launcher
script). All flags are opt-in unless noted.

| Flag | Purpose |
|------|---------|
| `-Dstorm.server=true` | **Required on the dedicated server.** Tells the bootstrap agent to target `GameServer` instead of `MainScreenState`. |
| `-DstormType=local` | Load Storm from `~/Zomboid/Workshop/storm` instead of the Steam workshop path. Local development only. |
| `-DLOG_LEVEL=DEBUG` | Storm log verbosity (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`). Default `INFO`. |
| `-Dstorm.http.port=<port>` | Start Storm's HTTP server on `<port>`. Required for every HTTP endpoint (hot-reload + runtime tuning). Conventionally `41798` on the dedicated server and `8089` on the client. |
| `-Dstorm.hotreload=true` | Register the `/reload` and `/eval` developer endpoints. See [Developer Hot-Reload Endpoints](http-api.md#developer-hot-reload-endpoints). **Local development only.** |
| `-Dstorm.hotreload.eval.classes=<dir>` | Directory holding the compiled `EvalScript.class` (required by `/eval`). |
| `-Dstorm.hotreload.eval.source=<dir>` | Optional. Directory holding `EvalScript.java`; enables a staleness guard. |
| `-DprometheusPort=<port>` | Start PZ's built-in Prometheus HTTP server on `<port>`. Required to scrape Storm + `pz_*` + `jvm_*` metrics at `/metrics`. (PZ flag — Storm registers into PZ's default registry.) |
| `-DprometheusHost=<host>` | Hostname/IP the server reports for itself in metrics endpoints. Defaults to `GameServer.ip`. (PZ flag.) |

## Sandbox options (performance knobs)

All Storm performance knobs are exposed as sandbox options on the "Storm | Performance" tab in
the world setup UI. Edit them through the admin UI before world creation, or hand-edit
`<SaveName>.ini` between runs. The Storm sandbox applier reads every option on
`OnServerStarted` and pushes it through the same live setter the HTTP endpoints use — so the
sandbox value always overrides any leftover `-D` system property at server start.

| Sandbox option | Default | Range | Effect | Live-tunable via |
|---|---|---|---|---|
| `Storm.ServerFps` | `10` | `1..240` | Unified server FPS. Drives the main-loop tick gate (`intervalMs = round(1000 / fps)`), `PerformanceSettings.getLockFPS()` on the server, and the `IsoPhysicsObject.update()` FPS scalar — all three together. `10` = vanilla 10 TPS. Keep the three aligned to avoid physics drift; this is exactly what the sandbox option guarantees. | `POST /storm/server/fps` |
| `Storm.AnimalLOSTickInterval` | `1` | `0..64` | Per-animal stride for `IsoAnimal.updateLOS()`. `1` = vanilla every tick. Larger = each animal scans LOS every Nth tick (cheaper). `0` disables animal LOS entirely. | `POST /storm/animalLOS/tickInterval` |
| `Storm.ZombieCullThreshold` | `500` | `0..99999` | Storm-controlled cull target. `500` = vanilla cap (default); the threshold patch also fixes vanilla's over-cull bug so the count converges instead of being mass-deleted ~10%/frame on overshoot. Larger = allow more live zombies before culling. `0` disables culling entirely (no zombies ever queued for deletion). | `POST /storm/server/zombieCull/threshold` |
| `Storm.ServerLosThreads` | `1` | `1..16` | Concurrent ServerLOS worker count. `1` = vanilla single-threaded baseline. The helper pool always pre-allocates 15 threads regardless; this only controls how many receive work each tick. Typical busy-server value `4..12`. | `POST /storm/serverLos/threads` |

The three subordinate fps controllers (tick interval, `lockFps`, physics fps) are not individually
tunable — `Storm.ServerFps` and `POST /storm/server/fps` are the only knobs, and both move all
three together.

The matching `storm_*` Prometheus gauges (`storm_server_tick_interval_seconds`,
`storm_server_lock_fps`, `storm_iso_physics_server_fps`, `storm_animal_los_tick_interval`,
`storm_zombie_cull_threshold`, `storm_server_los_threads`) reflect the currently-applied value
regardless of whether sandbox or HTTP wrote it last.

### Migrating from `-D` flags

The previous `-Dstorm.server.tickIntervalMs`, `-Dstorm.server.lockFps`,
`-Dstorm.isoPhysics.serverFps`, `-Dstorm.animalLOS.tickInterval`, `-Dstorm.zombieCullThreshold`,
`-Dstorm.disableZombieCull`, and `-Dstorm.server.fps` flags are no longer read by Storm — set
the corresponding sandbox option in the world INI instead. The three subordinate fps flags fold
into the single `Storm.ServerFps` knob (default `10`); `-Dstorm.disableZombieCull` folds into
`Storm.ZombieCullThreshold = 0`. `-Dstorm.serverLos.threads` is the one exception: it still
initializes the value at JVM startup (useful for tests), but the sandbox applier overwrites it
with `Storm.ServerLosThreads` at server start, so production servers should only set the
sandbox option.

## Production launcher example (Linux)

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
    -Dstorm.hotreload=true \
    -Dstorm.hotreload.eval.classes=/home/pzuser/lua-scripts/eval-scripts \
    -DprometheusPort=9092 \
    -DprometheusHost=<your-host>"

# Performance knobs (LOS threads, animal-LOS stride, zombie cull, tick/lockFps/physicsFps)
# live in the world INI as sandbox options now — set them once in the world setup UI on the
# "Storm | Performance" tab, or hand-edit <SaveName>.ini between runs. See the sandbox table
# above for names, defaults, and ranges.

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
