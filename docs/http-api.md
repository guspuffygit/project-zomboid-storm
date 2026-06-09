# HTTP API

Storm runs its own HTTP server in-process for runtime inspection and tuning. It
serves two endpoint families, both gated on `-Dstorm.http.port=<port>`:

- **Runtime tuning** — always available when the HTTP server is up. Inspect and
  change live-tunable knobs without restarting.
- **Developer hot-reload** — opt-in via `-Dstorm.hotreload=true`. Lua / Java
  hot-reload for local iteration.

Conventionally the server uses port `41798` and the client uses `8089` so the two
JVMs don't collide.

## Runtime Tuning Endpoints

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
| GET / POST | `/storm/server/fps` | `fps=<n>` | Unified server-fps knob — applies one fps to all three subordinate controllers (tick interval, lockFps, IsoPhysicsObject fps) atomically. Backs the `Storm.ServerFps` sandbox option. |
| GET / POST | `/storm/serverLos/threads` | `n=<n>` | Parallel server-LOS worker thread count (`1..16`). Backs the `Storm.ServerLosThreads` sandbox option. |
| GET / POST | `/storm/animalLOS/tickInterval` | `ticks=<n>` | Animal-LOS scan period in ticks (`0..64`; `0` disables). Backs the `Storm.AnimalLOSTickInterval` sandbox option. |
| GET / POST | `/storm/server/zombieCull/threshold` | `n=<n>` | Target live-zombie population for the cull (`0..99999`; `0` disables culling entirely). Backs the `Storm.ZombieCullThreshold` sandbox option. |
| GET | `/storm/server/players` | — | Currently-connected players (`username`, `steamId`, `ip`). |
| GET | `/storm/ram-allocations` | — | Per-player JVM RAM allocations reported by connected clients. |

Mod authors can register additional endpoints by annotating a handler method
with `@HttpEndpoint(path = "...", method = "GET"|"POST")` on any class discovered
by `StormEventDispatcher`. The dispatcher rejects handlers with the wrong return
type or signature at registration time and serves them on a shared thread pool.

```bash
# Read current server fps (vanilla 10 TPS)
curl http://localhost:41798/storm/server/fps
# -> {"tickIntervalMs":100,"tps":10.00,"lockFps":10,"physicsFps":10}

# Bump the unified server fps to 20 (50 ms tick, lockFps 20, physicsFps 20)
curl -X POST 'http://localhost:41798/storm/server/fps?fps=20'
# -> {"requestedFps":20,"appliedFps":20,"tickIntervalMs":50,"tps":20.00,"lockFps":20,"physicsFps":20}

# Disable the zombie cull pass entirely (threshold 0 = never queue any zombie for deletion)
curl -X POST 'http://localhost:41798/storm/server/zombieCull/threshold?n=0'
# -> {"requested":0,"applied":0}
```

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
