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

## Game-Port TCP Server

Separate from the backend server above, Storm servers also open a **TCP** HTTP
server on the game's UDP port number (`DefaultPort`, usually 16261 — TCP and
UDP port spaces are independent, so both bind simultaneously). It starts on
`OnServerStarted`, is **on by default**, and is disabled with
`-Dstorm.gameport.http.enabled=false`. Storm clients use it to move
connection-phase data off RakNet's 1&nbsp;KB-packet UDP transfer machinery.

The table below is the endpoint reference. For the design behind it — session
binding, which join stages are diverted, the fail-soft rules, the chunk-diversion
invariants and measured mass-join scaling — see
[Game-Port TCP World Loading](game-port-tcp-loading.md).

This surface is **internet-facing**: it uses a fully separate endpoint registry
(`@GameHttpEndpoint`), so backend endpoints (`/eval`, `/reload`, client-mod
files, …) can never leak onto it. Firewall note: port-forward rules are
per-protocol — an existing UDP rule for the game port does **not** open TCP;
forward TCP on the same port or clients silently fall back to UDP.

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/storm/ping` | Plain-text Storm version. Client reachability/capability probe. |
| POST | `/storm/handshake` | Marks the caller's RakNet connection as a Storm connection. Body `{"steamId": "...", "stormVersion": "..."}`; the claim binds only if a live UDP connection matches the TCP source IP **and** the steamId — the one vanilla stamped at login (that connection must be logged in; two indistinguishable logged-in matches from one IP, possible only in nosteam mode, are refused), or before the LoginPacket the one RakNet reports for the peer (the match must then be the only pre-login connection from that IP). Returns `{sessionToken, serverStormVersion}`; the token authenticates later requests via the `X-Storm-Session` header and expires with the RakNet connection. |
| GET | `/storm/game/request-data?id=…` | Authenticated, logged-in connection only (`LoginOnServer`). Serves one loading-phase bulk payload (`ZombieOutfitDescriptors`, `PlayerZombieDescriptors`, `RadioData`, `WorldMap`) as one octet-stream response instead of vanilla's 1&nbsp;KB `PartData` UDP packets with 200&nbsp;KB ACK windows. |
| GET | `/storm/game/player-profiles` | Authenticated, logged-in connection only (`LoginOnServer`). All saved network-character profiles for the connection in one JSON response (vanilla: up to 4 sequential `LoadPlayerProfile` UDP round trips). |
| POST | `/storm/game/chunks` | Authenticated, logged-in connection only (`LoginOnServer`). Loading-phase world-chunk download: body `{"requests":[{requestNumber,wx,wy,crc}]}` (max 20 per batch — vanilla's per-tick ccr size), binary response with one verdict per request: zlib chunk data, not-required (+`sameOnServer`), or retry (server still loading the cell). Gameplay-phase streaming stays on UDP. |
| POST | `/storm/game/login-queue` | Authenticated, needs `LoginOnServer`. Replaces the `LoginQueueRequest` UDP packet: runs the vanilla `LoginQueue` admission on the main thread and returns the first reply (`ConnectionImmediate` / `PlaceInQueue` as `QueuePacket` client-format bytes, no packet header) or 204. Opens the connection's TCP outbox. |
| GET | `/storm/game/login-queue?wait=ms` | Authenticated. Next pending queue message for the connection (200 bytes / 204 none / 410 outbox closed). `wait` is capped at 1 s. |
| POST | `/storm/game/login-queue/done` | Authenticated, needs `LoginOnServer`. Body `{"loadingMillis": n}`. Replaces `LoginQueueDone`: runs the vanilla handler (slot release, next player) and closes the outbox; the 200 is the echo. |
| POST | `/storm/game/login` | Authenticated (no capability: the connection has no role yet). Body is the vanilla `LoginPacket` bytes (no header, max 4 KB). Runs the vanilla login handler on the main thread and returns every packet it answered with as `[short id][int len][bytes]…` — `ConnectionDetails` is collapsed to one `FullData` frame. Idempotent per connection (409 once logged in), so the client retries a lost response safely. |
| POST | `/storm/game/client-event` | Authenticated. Body `{"event": "...", "detail": "..."}` (max 2 KB; event ≤ 64 chars, detail ≤ 512, control characters stripped). Writes one warning to the server log with the connection id; 1 per 5 s per connection, 429 above that. Used by the client loading watchdog to report packets its loading drain discarded. |
| POST | `/storm/game/checksum` | Authenticated, needs `LoginOnServer`. One round of the Lua/script/anim checksum exchange: body is a client-format `ChecksumPacket` (no header, max 64 KB), response is the server's reply packet in the same form. The vanilla handler runs on the main thread, so `checksumState`, AntiCheat and userlog side effects are unchanged. |

Handlers register with `@GameHttpEndpoint(path = "...", method = ...)` — same
signature rules as `@HttpEndpoint`, different registry. Client-side, the
`storm-tcp-channel` watcher dials the handshake automatically once the UDP
connection is up, retrying rejections for up to two minutes because the server
cannot bind the session until it has processed the client's LoginPacket;
`RequestDataOverTcpPatch`, `PlayerProfileOverTcpPatch`, `LoginQueueOverTcpPatch`
and `ChecksumOverTcpPatch` divert the loading-phase transfers when a session
exists and fall back to the vanilla UDP paths on any failure. With all of them
active, UDP carries only the RakNet connect/login handshake and gameplay-phase
traffic (chunk streaming, entity sync). Server-to-client queue messages are
captured at `PacketType.send` (`PacketTypeSendDivertPatch`, both JVMs) into a
per-connection outbox; a UDP `LoginQueueRequest` from the same connection
closes the outbox and replays anything pending, so a client that falls back
mid-queue keeps its place.

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
