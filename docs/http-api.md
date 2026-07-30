# HTTP API

Storm runs its own HTTP server in-process on the dedicated-server JVM for
runtime inspection. It serves two endpoint families, both gated on
`-Dstorm.http.port=<port>`:

- **Inspection** — always available when the HTTP server is up. Read live
  server state without restarting.
- **Developer hot-reload** — opt-in via `-Dstorm.hotreload=true`. Lua / Java
  hot-reload for local iteration against a local dedicated server.

Conventionally the server uses port `41798`.

## Inspection Endpoints

When Storm's HTTP server is enabled (`-Dstorm.http.port=<port>`), the following
endpoints are always available. All return JSON unless noted.

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/health` | Liveness probe. Always returns `200 OK`. |
| GET | `/storm/version` | Plain-text Storm version string. |
| GET | `/storm/server/players` | Currently-connected players (`username`, `steamId`, `ip`). |
| GET | `/storm/ram-allocations` | Per-player JVM RAM allocations reported by connected players' game clients over `sendClientCommand` from Storm-shipped Lua. |
| GET | `/storm/client/manifest` | Server JVMs only (`-Dstorm.server=true`). Manifest of the operator-curated client-mods directory (`stormVersion`, `dirs`, `files` with SHA-256 + size, `workshopItems` from server.ini) consumed by the [Storm Launcher](launcher.md). |
| GET | `/storm/client/file?path=…` | Server JVMs only. Downloads one manifest file (path-validated, read-only). |

Mod authors can register additional endpoints by annotating a handler method
with `@HttpEndpoint(path = "...", method = "GET"|"POST")` on any class discovered
by `StormEventDispatcher`. The dispatcher rejects handlers with the wrong return
type or signature at registration time and serves them on a shared thread pool.

## Developer Hot-Reload Endpoints

Storm ships two optional HTTP endpoints for iterating on a running game without restarting it:

- `POST /reload` — compiles and runs a Lua snippet in the live `LuaManager` environment.
- `GET /eval` — loads and runs a freshly compiled `EvalScript` Java class.

They are **off by default** and intended for local development only.

> ⚠️ Both endpoints execute arbitrary code in the dedicated-server JVM. Only enable them on a
> trusted local development server — never on a public-facing production server.

### Enabling

The endpoints ride on Storm's built-in HTTP server, so you need both flags below. `/eval` no longer
needs a classes directory — the caller ships the compiled bytecode in the request body.

| Flag | Purpose |
|------|---------|
| `-Dstorm.http.port=<port>` | Starts Storm's HTTP server on `<port>` (required for any endpoint). |
| `-Dstorm.hotreload=true` | Registers `/reload` and `/eval`. Without it they return `404`. |

Example (Linux dedicated server, local install):

```bash
./start-server.sh \
  -javaagent:~/Zomboid/Workshop/storm/Contents/mods/storm/bootstrap/storm-bootstrap.jar \
  -Dstorm.server=true \
  -DstormType=local \
  -Dstorm.http.port=41798 \
  -Dstorm.hotreload=true \
  -- \
  -servername yourserver
```

### `POST /reload` — Lua

Send the Lua source as the **raw request body**. It is compiled with Kahlua and run in the
dedicated server's `LuaManager.env`; the chunk's return value comes back in the response.

```bash
curl -X POST --data-binary 'return "pong"' http://localhost:41798/reload
# -> OK: pong
```

Responses:

- `OK` — chunk ran, no return value.
- `OK: <value>` — chunk returned a value.
- `ERROR: <message>` — compilation or execution failed (HTTP `200`, body carries the Lua error).
- `400` — empty request body.

### `POST /eval` — Java

Write an `EvalScript.java` in the **default package** (no `package` line) with a
`public static Object run()` method, compile it locally, and POST the raw `.class` bytes as the
request body. Storm defines the class in a fresh one-shot `ClassLoader` per request, so each call
sees exactly the bytecode it posted — no shared directory, no stale-class window. The script has
full access to `zombie.*` and `io.pzstorm.*`.

```java
// EvalScript.java  (default package — no package declaration)
public class EvalScript {
    public static Object run() {
        return "server=" + zombie.network.GameServer.server;
    }
}
```

```bash
# Compile against projectzomboid.jar + the Storm jar, then POST the .class bytes:
javac -cp '<projectzomboid.jar>:<storm.jar>' -d /tmp/eval EvalScript.java
curl -X POST --data-binary @/tmp/eval/EvalScript.class \
  -H 'Content-Type: application/java-vm' \
  http://localhost:41798/eval
# -> server=true
```

The endpoint returns `String.valueOf(run())`, or an `ERROR:` line with a stack trace if the body is
not a valid class file (missing the `0xCAFEBABE` magic), `defineClass` rejects it, or `run()` throws.
`400` is returned for an empty body. Both endpoints run on the dedicated-server JVM's HTTP thread.
