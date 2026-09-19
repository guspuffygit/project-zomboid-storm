# Game-Port TCP World Loading

Storm moves the whole multiplayer join — login, queue, checksum, bulk payloads,
player profiles and the initial chunk download — off RakNet UDP and onto an HTTP
channel that runs over TCP on the game's own port number. Gameplay traffic stays
on UDP.

Applies to Storm clients only. A vanilla client never handshakes, so every
vanilla code path runs exactly as before.

Related reading: [HTTP API](http-api.md) for the endpoint reference,
[What Storm Changes](what-storm-changes.md) for the surrounding client-side
feature list, [Storm Launcher](launcher.md) for how players get a Storm client.

## Why

Vanilla's connection phase is a request/reply chain of one-shot UDP packets with
no application-level acknowledgement, retry or deadline on either side. RakNet
delivers reliably, but every layer above it can still drop a packet silently.

| Vanilla failure | What the player sees |
|---|---|
| `Login` datagram lost (sent once from `UdpEngine.connected()`) | "Connecting…" until the server's 5 s pre-login reap; nothing logged either side |
| Per-type send limiter exceeds its budget | Silent `cancelPacket()`; the join hangs |
| A loading-phase handler throws in `gameLoadingDealWithNetData` | Packet discarded; the client waits forever for data already delivered |
| Local UDP port collision in `GameClient.startClient` | `clientStarted` stays false, nothing retries, UI sits on "Contacting server…" |
| `LoginQueueDone` echo lost | Client stalls holding a queue slot |

The bulk transfers were slow rather than fragile. `RequestData` ships 1 KB
`PartData` packets gated on 200 KB client ACK windows; chunk streaming ships
1000-byte `SentChunk` fragments, one 20-chunk batch per connection per server
tick; player profiles cost up to 4 sequential round trips polled at 50 ms.

TCP gives the request an answer. Either the HTTP call returns or it times out,
and the caller knows which.

## What moves to TCP

Join stages in order. "Gate" is the capability the server requires on the
session.

| Stage | Vanilla transport | Storm endpoint | Gate |
|---|---|---|---|
| Session bind | — | `POST /storm/handshake` | none (binds by steamId + source IP) |
| Login + ConnectionDetails | `LoginPacket` → `ConnectionDetails` as `PartData` frames | `POST /storm/game/login` | none (the packet assigns the role) |
| Login queue request / grant / done | `LoginQueueRequest`, `ConnectionImmediate`/`PlaceInQueue`, `LoginQueueDone` | `POST`/`GET /storm/game/login-queue`, `POST …/done` | `LoginOnServer` |
| Lua / script / anim checksum | `ChecksumPacket` round trips | `POST /storm/game/checksum` | `LoginOnServer` |
| Bulk payloads (outfits, zombie descriptors, radio, world map) | `RequestDataPacket` + `PartData` | `GET /storm/game/request-data?id=…` | `LoginOnServer` |
| Saved player profiles | up to 4 × `LoadPlayerProfile` | `GET /storm/game/player-profiles` | `LoginOnServer` |
| Initial chunk grid | `RequestZipList` → `SentChunk` | `POST /storm/game/chunks` | `LoginOnServer` |
| Client diagnostics | — | `POST /storm/game/client-event` | authenticated only |

Gameplay-phase chunk streaming stays on UDP by design. The gate is
`GameClient.instance.playerConnectSent`, which flips at "Click to start".

With every stage active, UDP carries the RakNet connect handshake and
gameplay traffic. Nothing else.

## Session model

The client's `StormTcpChannel` watcher thread polls for
`GameClient.connection != null`, then dials TCP on the same `host:port` it just
dialed over UDP and posts `/storm/handshake`. The reply carries a session token
that authenticates every later request in the `X-Storm-Session` header.

The server binds a claim two ways, tried in this order.

- **Post-login** (`StormTcpSessionRegistry.handshake`) — a live logged-in
  `UdpConnection` whose steamId matches the claim and whose UDP source IP equals
  the TCP source IP. Two indistinguishable logged-in matches from one IP are
  refused; that only happens in nosteam mode, where vanilla stamps no steamId.
- **Pre-login** (`handshakePreLogin`, server main thread) — the one connection
  from that IP that has not sent a LoginPacket. In Steam mode the claimed
  steamId must equal `UdpEngine.getClientSteamID(guid)`, the same source vanilla
  stamps from. Ambiguous matches are refused.

The pre-login path exists so the client can hold its Login and post it over TCP.
Binding re-stamps `UdpConnection.connectionTimestamp` so vanilla's 5 s
connection-attempt reap does not race the login post, bounded to one extra
window.

Tokens are validated lazily against `UdpEngine.getActiveConnection(guid)`, so a
dropped player invalidates the session with no disconnect hook. RakNet keeps a
GUID across reconnects, so a new binding on a known GUID replaces the stale
session rather than being rejected by it.

### Handshake retries

RakNet surfaces the connection on the server slightly after `GameClient.connection`
goes non-null on the client, so early 403s are normal and a fixed attempt count
cannot work. The watcher separates the two outcomes.

| Outcome | Meaning | Budget |
|---|---|---|
| `REJECTED` (403) | Server has not surfaced the connection yet | wall clock, 120 s from first attempt |
| `UNREACHABLE` (connect error) | No Storm, game-port server off, TCP unforwarded | 5 attempts, then remembered per `host:port` |

An exhausted unreachable budget sets `mayEstablish()` false for that server, so
`StormLoginOverTcp` stops holding the Login for it.

### Threat model

The bind requires the TCP source IP to equal the UDP source IP, which defeats
off-path hijack. A same-NAT attacker who also knows the victim's steamId can
bind the victim's pre-login connection and log it in under the attacker's own
credentials — that gains nothing beyond what a same-NAT UDP injection already
could. `POST /storm/game/login` carries the vanilla LoginPacket bytes, cleartext
exactly as vanilla sends them over UDP, so the channel does not widen credential
exposure. Login bodies are never logged.

## Mechanism

### Packet diversion

`PacketTypeSendDivertPatch` (both JVMs) hooks `PacketTypes.PacketType.send`.
Vanilla still builds every packet through `startPacket` → `doPacket` → `write`;
only the final send is swapped for a buffer copy plus `cancelPacket()`.
`StormPacketDivert` decides per send.

1. A thread-local `Scope` for that connection and packet type. Used for
   request/reply exchanges vanilla drives with nested sends — the checksum state
   machine on both sides, and the server's whole login reply.
2. Server only — a `LoginQueueRequest` send to a connection with an open
   `StormLoginQueueMailbox`. Those sends come from the queue update loop and
   other connections' `loadNextPlayer`, so no scope can enclose them.
3. Client only — the `Login` packet, handed to `StormLoginOverTcp`.

Separately, and not a diversion: the client lets `Login`, `PlayerConnect`,
`LoginQueueRequest`, `LoginQueueDone`, `Checksum` and `RequestData` bypass
vanilla's per-type send limiter, because that limiter answers an exceeded budget
with a silent `cancelPacket()`.

### Main-thread bridge

`StormServerTaskQueue` runs work from HTTP pool threads on the server main
thread, drained once per tick by `ServerTickAdvice`. Every loading endpoint uses
it, because the state it touches is main-thread state that vanilla packet
handlers own.

| Endpoint work | Thread |
|---|---|
| `LoginPacket.parse` + `processServer`, `LoginQueue` admission, profile SQLite query, `RequestData` serialization, `ServerMap.getChunk` + `IsoChunk.Save` | server main, via the queue |
| Chunk disk reads (`IsoChunk.SafeRead`), CRC, zlib | HTTP pool thread |

Callers block on the future with a 15 s timeout and answer `503 server busy`
rather than wedging the pool.

### Login over TCP

The client holds the Login at `PacketType.send`, waits up to 2 s for the
handshake, and posts to `/storm/game/login` with 2 attempts of 4 s each. The
server runs the vanilla parse and `processServer` on the main thread inside a
divert scope over all packet types, and returns everything vanilla answered with
as `[short id][int len][bytes]…`. `ConnectionDetails`, normally ACK-driven
`PartData` frames, collapses to one synthetic `FullData` frame. The client feeds
the frames through `GameClient.addIncoming` on the main thread from `OnFETick`.

Three guards make the post idempotent, because vanilla's `processServer` is not.

- One in-flight task per `UdpConnection` **instance**, which a concurrent retry
  joins instead of duplicating.
- A short-lived reply cache keyed by GUID but honored only for the instance that
  produced it, so a reconnect on the same GUID cannot receive a stale reply.
- `LoginPacketDuplicateGuardPatch` (server) drops a second Login on an already
  logged-in connection, which makes a TCP-then-UDP retry harmless.

### Chunk diversion invariants

Violating any of these hangs loading or corrupts `pendingRequests`.

- Divert at `RequestZipListPacket.write` and stub the UDP packet with
  `putInt(0)`. Dispatch the TCP fetch on `WorldStreamer.updateMain` **exit** —
  `updateMain` publishes `sentRequests` after `write`, so an earlier delivery
  matches nothing and is silently dropped.
- Deliver every TCP result through vanilla `receiveChunkPart` /
  `receiveNotRequired`. Pending-request retirement and cancel sweeps happen only
  inside those methods, so bypassing them means `isBusy()` never clears. A
  synthetic single-fragment `SentChunk` works because the receive side is
  size-agnostic.
- Hold `StormChunksOverTcp.RECEIVE_LOCK` around those two methods. They are
  vanilla-called from the UdpEngine thread, mutate a non-thread-safe list, and
  Storm's worker now calls them too.
- Batch at 20, matching vanilla's per-tick `ccr` size, so main-thread cost per
  request equals vanilla's cost per tick.
- A chunk that is neither loaded nor on disk gets a `RETRY` verdict; the cell may
  be mid-load. The client retries 3 times at 250 ms, mirroring vanilla's
  re-queue rule.

## Fail-soft rules

Every diversion degrades to the vanilla UDP path. A broken patch produces
vanilla behavior, never a stuck client.

| Component | Failure behavior |
|---|---|
| `StormTcpChannel` | No session; every divert helper declines and vanilla runs |
| `StormLoginOverTcp` | Releases the held packet over UDP within 8 s |
| `StormLoginQueueOverTcp` | 3 consecutive poll failures re-issue the UDP request; the mailbox replays captured messages over UDP, so the client keeps its queue place |
| `StormChecksumOverTcp` | Resets the comparer to `Init`; the vanilla exchange starts from scratch |
| `StormRequestDataOverTcp` | Downloads all four payloads before applying any, so a mid-download failure touches no state |
| `StormPlayerProfilesOverTcp` | Installs nothing; the vanilla method's own UDP path runs |
| `StormChunksOverTcp` | Per-session breaker stops diverting; the requests still unanswered are re-issued over UDP under their original request numbers. Vanilla 42.20.4 has no per-request resend, only the 60 s no-progress abort |
| `StormPacketDivert` | `tryDivert` returns false on any buffer problem and the vanilla send runs |

## Rider fixes

Two client bugs fixed alongside, both independent of the transport change.

**Client socket retry.** `GameClient.startClient` draws its local UDP port at
random from a 10 000-port window and binds once. On a machine with a few hundred
of those ports taken — routine on Windows running WSL or Hyper-V — RakNet answers
`SOCKET_PORT_ALREADY_IN_USE`, vanilla swallows it, and the connect request has
already been consumed so nothing retries. `GameClientStartClientRetryPatch`
re-runs the constructor up to 10 times, re-rolling the port each time, and fires
`OnConnectFailed` when every draw fails or the failure was in `Connect` rather
than the bind.

**Loading watchdog.** `ClientLoadingWatchdog` samples `GameLoadingState.loader`,
logs a heartbeat, and dumps loader plus main-thread stacks and the
`FileSystemImpl` task queues once nothing changes. It also watches the game log
for `gameLoadingDealWithNetData`'s "Error with packet of type" line, the only
trace vanilla leaves when a loading handler throws and discards a packet, and
reports it to the server via `POST /storm/game/client-event`. Detection only —
a forced disconnect mid-load crashes the vanilla client.

## Operations

**Firewall.** Port-forward rules are per-protocol. An existing UDP rule for the
game port does **not** open TCP. Without a TCP rule, clients hit the unreachable
budget and silently stay on UDP.

**Disable.** `-Dstorm.gameport.http.enabled=false` turns off the game-port
server; every client falls back to vanilla UDP.

**Metrics.** `storm_gameport_http_requests_total{method,path,status}` and
`storm_gameport_http_request_duration_seconds{method,path}`. Storm's
connection-stage metrics and the stalled-connection reaper see a TCP joiner
exactly like a UDP one, because the vanilla handlers still run.

**Logs.** `Marked Storm connection: guid=… steamId=… ip=… clientStorm=…` on the
server marks a successful bind. `Storm TCP channel established to …` on the
client, `Storm TCP channel unavailable for this server; staying on UDP` when the
budget runs out.

## Measured scaling

Measured 2026-09-15 with `./gradlew tcpLoadTest -Dstorm.perftest.clients=N` on a
62 GB WSL box, at 20/40/50/75/100/130/165/200 simultaneous joiners.

| Metric | Result |
|---|---|
| Failures | Zero 503s, zero 401s, zero retry-exhausted chunks at every size. 33 800 chunk verdicts at 200 clients were all `data` |
| Throughput | ~90 req/s through 100 clients, settling to 64–74 above that |
| Per-client p50 | Grows linearly, 1 934 ms at 20 clients → 31 757 ms at 200 |
| Worst single request | 5 280 ms, on `/storm/handshake` at 200 clients |
| Memory | ~230 MB per client; server 10.2 GB idle → 54.9 GB at 200 players |

The throughput shape follows `GamePortHttpServer.HANDLER_THREADS = 4` draining
`StormServerTaskQueue` once per 50 ms tick. The 15 s queue timeout is never
approached. RAM is the ceiling, not Storm — the test host ran out first.

## Testing

Unit and patch tests run in `check` as usual. The mass-join test is tagged
`perf` and excluded from `test`, because it boots a dedicated server and spawns
one child JVM per client.

```
./gradlew tcpLoadTest
./gradlew tcpLoadTest -Dstorm.perftest.clients=40 -Dstorm.perftest.budgetSeconds=300
```

`StormTcpLoadingClient` replays one join's TCP traffic headlessly, in the same
order and with the same batching and retry rules as the real client. Every
non-200 throws, so a starved pool or a stalled tick fails a client rather than
slowing it.

Three harness traps cost real time on a rerun.

- `LoginQueue` parks client 101+ unless `MaxPlayers` is raised in
  `build/tmp/integrationTest/ServerExtension/zomboid/Server/stormtest.ini`.
  `ServerExtension` writes that key only when the ini is absent, and the cap is
  254. Restore it to 100 afterward.
- A single lost RakNet dial looks like a Storm failure. `LiveServerClient` waits
  on a latch only `ID_CONNECTION_REQUEST_ACCEPTED` counts down, so a RakNet
  give-up surfaces as "RakNet handshake did not complete within PT2M". Seen once
  in ~800 dials. Rerun before believing it.
- `AntiCheatPlayer` "update failed" kicks are noise. The replica clients log in
  but never send `PlayerUpdate`, so the counters climb whenever a run hangs.
  They are a symptom of a stalled run, never its cause.

The test stamps distinct steam ids through `POST /eval` because the server runs
`-nosteam`, where every connection keeps steamId 0 and all children dial from
127.0.0.1. The same collision is real for nosteam servers whose players share a
NAT.

## Maintaining this on a PZ update

Every item below is a vanilla surface Storm reaches into. Re-validate each one
when the game version bumps.

| Surface | Used for |
|---|---|
| `PacketTypes.PacketType.send` | Diversion hook (both JVMs) |
| `UdpConnection.bb`, 3-byte packet header (`(byte)134` + short ordinal) | Reading the built packet out of the buffer |
| `LoginPacket.processServer`, `RequestDataPacket.largeFileBb`, `RequestDataManager.disconnect` | Login reply capture and the synthetic `FullData` frame |
| `WorldStreamer.updateMain` / `receiveChunkPart` / `receiveNotRequired`, `RequestZipListPacket.write` | Chunk diversion |
| `NetChecksum.Comparer.beginCompare`, `ChecksumPacket.parse` / `parseServer` | Checksum exchange |
| `LoadingQueueState.update`, `QueuePacket` client format | Queue drain on the main thread |
| `ClientPlayerDB.clientLoadNetworkPlayer`, `GameClient.GameLoadingRequestData` | Profile and payload fast paths |
| `GameClient.playerConnectSent`, `GameClient.ip` / `port` / `connection` | Phase gate and dial target |
| `UdpEngine.getClientSteamID`, `UdpConnection.connectionTimestamp` | Pre-login binding |
| `RandAbstract`'s `Random` field | RNG gate before touching `GameClient` (located by type, not name) |
| `FileSystemImpl` private task queues | Watchdog stall dump (reflective, degrades to a note) |

One class-init trap in tests: constructing a `UdpConnection` class-initializes
`PacketType` → `AntiCheat` → `ServerOptions`, which draws from the game RNG.
Seed it with `RandStandard.INSTANCE.init()` in `@BeforeAll` or every test in the
class fails with `ExceptionInInitializerError`.
