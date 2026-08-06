# Storm Launcher

The Storm Launcher is a standalone pre-game UI (`launcher/` subproject →
`storm-launcher.jar`) for joining Storm-enabled servers. It deliberately loads
**zero Project Zomboid classes** — it is a plain Swing app with no third-party
dependencies that runs on the JRE bundled with the game (`jre64`, Java 25).

Because it runs *before* any game JVM exists, nothing has jars memory-mapped or
locked: Storm itself, its libraries, and java mods can all be replaced freely,
and the game only ever boots against already-updated files. This is the
restart-free answer to the Windows locked-jar problem that breaks in-game
workshop updates.

## What it does

1. **Server list** — add/edit/remove saved servers (`host`, game port, optional
   Storm HTTP port, server access password, in-game account credentials).
2. **Workshop query over the game port** — when the manifest is unavailable or
   lists no items (the usual case: almost nobody exposes the Storm HTTP port to
   players), the launcher asks the server directly over the port it is already
   joining. See [Workshop query over UDP](#workshop-query-over-udp). A server
   with no Storm on it at all cannot answer that either, so there is a third
   source: log in the way the game does and read the list the server sends
   every joining client. See
   [Mod list from the login handshake](#mod-list-from-the-login-handshake).
3. **Steam workshop pre-update** — the server publishes its required workshop
   item ids in the manifest (`workshopItems`, from `server.ini`). Before
   launching, the launcher asks the running Steam client to subscribe + update
   each item via Steam's own UGC API (the platform's `steam_api` library bound
   through `java.lang.foreign` — no SDK, no writes into steamapps; Steam
   manages its own content dir). **Storm's own workshop item is always first
   in that list**: clients get Storm from the workshop by default — a client
   with no Storm at all subscribes and downloads it, an outdated one is
   refreshed — even when the server publishes no items of its own. Items are
   brought to the exact state the game's join gate demands
   (`Subscribed|Installed`, no update pending), so the in-game "workshop
   items" download screen never appears and joins don't wedge on locked
   files. Every item gets a real `DownloadItem` call and the launcher waits
   for Steam's per-item download result callback — Steam's cached item state
   is stale from the moment a new version is published until Steam happens to
   re-poll, so trusting `GetItemState` alone would happily report an outdated
   item as current. Runs in a child JVM with cwd = game dir (where
   `steam_appid.txt` lives); Steam not running degrades gracefully to the
   vanilla in-game flow.
   When the server's own item list is unreachable (no manifest, no UDP reply),
   the launcher still pre-updates **every installed workshop item whose
   published version diverged from the local install** (`WorkshopStaleScan`):
   local install timestamps come from Steam's
   `steamapps/workshop/appworkshop_108600.acf`, published timestamps from the
   anonymous `GetPublishedFileDetails` Web API, and the comparison is the same
   `!=` the game's `ConnectToServerState.WorkshopConfirm` uses to force its
   "install workshop updates" dialog — so anything that dialog would catch is
   already fresh before the game boots. Items the player never installed are
   the one remaining first-join case the in-game subscribe flow still covers.
   (An explicitly configured bootstrap dir outside any workshop item turns the
   Storm self-update off — a pinned custom install is not fought.)
4. **Java mod sync** — fetches `GET /storm/client/manifest` and mirrors the
   published mod tree into `<home>/Zomboid/storm/launcher/mods/<host_port>/`.
   Every file is SHA-256 verified; files that drop out of the manifest are
   deleted. A Storm version skew between client and server logs a warning.
5. **Full auto-join (optional)** — with a username (and optionally a saved
   account password) on the profile and *Auto-connect* ticked, the launcher
   writes a one-shot credential handoff
   (`<home>/Zomboid/storm/launcher/autojoin.properties`, `java.util.Properties`
   format), passes its path to the game JVM as `-Dstorm.autojoin.file=<path>`,
   and suppresses the vanilla `+connect` args. Storm-core client Java
   (`io.pzstorm.storm.client.LauncherAutoJoin`) reads and immediately deletes
   the file at the first main menu — credentials never linger — then fills and
   submits the vanilla `ServerConnectPopup` exactly like a human clicking
   CONNECT (`doHash=true`, so the launcher stores/passes the plaintext the
   player would have typed). Zero clicks from launcher to in-game. Because the
   handoff only fires when the property is present, a stale file can never
   auto-join a manually started game; the launcher also deletes handoffs older
   than ten minutes on startup. Any missing prerequisite falls back to the
   pre-filled popup flow. **Requires client Storm ≥ 2.5.1** (the first version
   whose client Java ships `LauncherAutoJoin` and the `-Dstorm.launcher.mods`
   loader): against an older installed Storm the launcher does not arm the
   handoff — arming it would strand the player at the main menu with the
   `+connect` args suppressed and nobody consuming the file — and instead
   falls back to the vanilla `+connect` flow. For the same reason, joining a
   server that publishes java mods hard-fails on a pre-2.5.1 client (the
   synced mods would silently never load — a guaranteed desync); let Steam
   update the Storm workshop item and join again.
6. **Game launch** — builds the client java command from the game's own
   `ProjectZomboid64.json` (mainClass, classpath, vmArgs, windows overlays),
   appends the Storm agent flags, `-Dstorm.experimental.clientperf=true`
   (Storm's experimental client performance patches are **on by default**;
   untick *"Experimental client performance fixes"* in Settings or pass your
   own `-Dstorm.experimental.clientperf=…` to override),
   a managed `-Xmx` replacing the game json's stock 3 GB heap (*Game memory*
   in Settings, identical on all three OSes: *Automatic* — the default —
   allocates half the system RAM plus 1 GB, capped at 16 GB, and shows the
   resulting size next to the checkbox; untick it for a manual 4–32 GB value;
   an explicit `-Xmx` among the user JVM args wins and suppresses the managed
   one),
   `-Dstorm.launcher.mods=<synced dir>`, any user JVM args, and — unless the
   auto-join handoff is armed — the vanilla `+connect host:port`
   (`+password <serverPassword>`) args, then spawns the JVM with the game
   directory as working directory. On linux/mac the launcher exports the same
   loader environment vanilla's `projectzomboid.sh` would (`LD_LIBRARY_PATH`
   with `linux64/`, the game dir and the bundled JRE's lib dir, plus the
   `libjsig`/`libPZXInitThreads64` preloads on linux; `DYLD_LIBRARY_PATH` on
   mac) so transitive native dependencies resolve for a direct JVM spawn.
   Game output is captured to `<home>/Zomboid/storm/launcher/logs/game.log`.

The client's `StormModLoader` scans the synced directory as an additional mods
root (after workshop/mods roots, so the server-published version wins mod-id
collisions). The directory layout is the standard mod layout — `<mod>/common/`,
`<mod>/42/<jars>`, `mod.info` — mirrored verbatim from the server.

Vanilla PZ behavior notes:

- `+connect` requires exactly `ip:port`; the game consumes it after the main
  menu loads and opens the connect popup. Vanilla has **no CLI for account
  credentials** — that is why the auto-join path goes through Storm's client
  Java instead, which drives the same popup with the credentials filled in.
  Without auto-join, the first join of a new server needs one manual CONNECT
  click in-game.
- The account password is saved in `launcher.json` **in plain text and only
  when "Save account password" is ticked** (the game's own ServerList DB
  stores an unsalted MD5-based hash — not meaningfully safer). Leave it
  unticked to keep auto-fill of everything except the password.
- Steam mode comes from the game's own vmArgs (`-Dzomboid.steam=1`). For
  non-Steam servers, tick *"Pass -nosteam"* on the profile (workshop
  pre-update is skipped for those).
- Workshop pre-update **subscribes** the player's Steam account to the
  server's items — the same thing the vanilla join flow does for unsubscribed
  items, and required by the game's join gate, which checks for exactly
  `Subscribed|Installed`. Bonus: Steam then keeps those items updated by
  itself whenever the game isn't running.

## Self-update: the staging loop

The launcher ships *inside* the Storm workshop item
(`<item>/mods/storm/launcher/`), and Steam fails an entire item update when
*any* file in the item dir is held open — even a byte-identical one. A JVM
holds its `-jar` open for the life of the process, so a launcher running in
place could never update the very item it ships in. Updating is not optional:
servers reject version-skewed clients.

So the launcher never runs from the item. `LauncherStage` re-enters the item's
own front door until the item stops changing:

```
[item jar]    copy self to <home>/Zomboid/storm/launcher/stage/<hash>/,
              exec the copy, exit            (holds the item; no Steam calls)
[staged jar]  wait for the parent to die  →  DownloadItem on the own item
                                             (zero open handles in the item)
              item's launcher jar == own jar →  settled: run normally
              differs                        →  exec the item jar again, exit
```

While a staged launcher checks its item, the UI is a single message —
*"Checking for updates and restarting..."* — so the window vanishing on a
restart hop is explained before it happens; the settled launcher then opens
the normal window (or continues a headless `--join`).

Design points:

- **Each version stages itself.** The restart goes through the *item's* jar
  rather than copying the new jar from the old process, so old code never
  needs to know what a newer launcher ships.
- **The loop condition is a content hash**, not Steam's per-item result —
  Steam reports "updated" even when nothing changed on disk. Three hops
  without converging logs a warning and continues on the current version.
- **Stage dirs are content-addressed** (`stage/<sha256-prefix>/`), so staging
  is idempotent, racing instances converge on the same copy, and everything
  but the running copy is swept at the next settle.
- **Identity survives staging.** The staged copy resolves "which item is
  mine" (own-item-first update order, steamapps discovery, game-dir
  detection) through the `--staged-from` origin, not its own location —
  otherwise a launcher staged from the dev or staging item would quietly
  pre-update prod.
- **Repo/dist builds never stage and never restart** — an explicit custom
  install is not fought; those keep the join-time update flow only.
- **Failure degrades to today's behavior.** If staging is impossible
  (antivirus, full disk) the launcher runs in place with a warning and skips
  self-update; if Steam is unreachable the join proceeds and the server's own
  version gate has the last word. The game itself still maps
  `bootstrap/agentlib.dll` while running, so item updates keep requiring the
  game to be closed — the loop only guarantees the *launcher* is never the
  thing in the way.

## Workshop query over UDP

The mod manifest is only reachable where the Storm HTTP port is, and on real
servers it usually isn't — it is an operator port, not a player port. So the
launcher has a second source for the workshop list that needs no extra port
open: it asks over the game's own UDP transport, before anything logs in.

- **Wire format** — a Storm-only packet pair carried inside PZ's normal user
  packet framing (`byte 134`, then a `short` id). Ids `0x7A17` (query) and
  `0x7A18` (reply) sit far above the vanilla `PacketType` ordinal range, and
  the payload starts with the magic `"STMQ"` plus a protocol version. Constants
  live in `io.pzstorm.storm.query.StormQueryProtocol`.
- **Server side** — `ServerQueryPatch` puts `StormQueryResponder` at the top of
  `GameServer.addIncoming`. That is deliberately *ahead* of vanilla's login
  gate, which force-disconnects any not-yet-logged-in connection that sends
  anything outside a five-packet allowlist; it also sidesteps vanilla's
  unknown-id path, which leaks the pooled `ZomboidNetData` it allocated. The
  reply carries the workshop item ids, the server mod ids, the Storm and game
  versions, the server name, and the player counts — all of it already visible
  to anyone who can join. Replies are capped per connection and the whole
  handler fails soft.
- **Client side** — the launcher may not touch PZ classes, and RakNet's
  connected layer (MTU, reliability, split-packet reassembly) plus PZ's BCrypt
  password hashing are not worth reimplementing. So the query runs in a child
  JVM on the game's own classpath: `io.pzstorm.storm.query.StormQueryClient`
  (in Storm core), which drives PZ's own `UdpEngine` and prints `key=value`
  lines that `io.pzstorm.launcher.ServerQuery` parses. It never logs in, so it
  never occupies a player slot.
- **Which port** — a Steam-mode server holds `DefaultPort` with its Steam
  socket and answers raw RakNet on `UDPPort` (conventionally `DefaultPort + 1`);
  a `-nosteam` server answers on `DefaultPort` itself. The child tries both, so
  the profile only needs the port players already type.
- **Degradation** — no Storm on the server, an older Storm, or a blocked port
  all end as "no reply", and the game's own in-game workshop flow still runs.

The result feeds the workshop pre-update only. It deliberately does **not**
become a `ModManifest`: that object also drives java-mod sync, whose deletion
pass would wipe the local mirror if handed a file list this query cannot
produce.

## Mod list from the login handshake

Both sources above need Storm on the server. Against a stock server — or one
whose Storm predates `StormQueryResponder` — neither answers, and
`WorkshopStaleScan` can only refresh items that are already installed. That
leaves the case a first-time player actually hits: **items the server requires
and the player has never had.**

Every PZ server already sends that list to every client that logs in. It is the
`ConnectionDetails` payload — server name, map, player cap, then the workshop
ids with their published timestamps, then the mod ids — pushed as a
`RequestData` transfer right after login. So the launcher's last source is a
real login: `io.pzstorm.storm.query.ServerModListProbe` (Storm core) in a child
JVM, driven by `io.pzstorm.launcher.ServerModList`.

- **It is a real client.** Steam mode, PZ's own `UdpEngine`, a real
  `LoginPacket` with the account password hashed exactly as
  `ConnectionManager.doServerConnect(doHash=true)` hashes it (MD5-hex, then
  BCrypt with PZ's fixed salt). Anything less and the server answers
  `InvalidUsernamePassword`. It disconnects the moment the payload is complete,
  so it holds a slot for a few seconds, not a session.
- **It suppresses the client half it does not need.** The probe's `UdpEngine`
  subclass overrides `connected()`, which in vanilla fires a voice-connect
  request and the automatic login; `askPing`/`sendQR`/`askCustomizationData`
  stay false. Login is then sent by hand.
- **Credentials go over stdin** as `java.util.Properties`
  (`accountPassword`, `serverPassword`), never as arguments — arguments are
  visible in every process listing on the machine. The child reads stdin to
  end-of-stream, so the launcher must close the pipe.
- **Steam-mode traps, all three found by live failure** (they are cheap to
  re-break):
  - The Steam handshake only advances while `SteamUtils.runLoop()` is pumped —
    vanilla pumps it per frame. Blocking on a latch without pumping looks
    exactly like a dead server ("RakNet handshake timed out").
  - `RakNetPeerInterface.connectionStateChangedCallback` fires
    `LuaEventManager.triggerEvent` when it runs on the thread that called
    `RakNetPeerInterface.init()` — which NPEs with no Lua environment. The probe
    calls `init()` on a throwaway thread so the callback always takes its
    off-main branch, which only logs and still calls `connected()`.
  - In Steam mode the native callback reaches the engine through
    `GameClient.instance.udpEngine`, not through whichever engine owns the
    socket, so the probe assigns itself there.
- **Chunked payload.** ATF's list arrives as ~2.3 MB in 1024-byte `PartData`
  frames; the server bursts 200 KB then waits for an ACK. The probe ACKs
  mid-transfer only — vanilla's `RequestDataManager.ACKWasReceived` walks its
  request list with an `i <= size` bound and throws server-side if an ACK
  arrives after the transfer is done.
- **Failures are all soft**: no username on the profile, no Steam, wrong
  password (`AccessDenied`/`Kicked` are reported and dropped), no Storm jar to
  run. Each ends as "no mod list" with the game's own workshop flow still to
  come. And nothing is trusted without the child's `STORM_MODLIST_OK` marker,
  so a child that crashes halfway through printing can never read as "this
  server requires no mods".

Ordering in `JoinFlow.serverWorkshopItems`: manifest → UDP query → login probe,
each only if the one before produced nothing. The probe is last because it is
the only one that costs a login. Its ids are unioned with `WorkshopStaleScan`'s,
which still runs: the server states what it needs, the scan catches everything
else on disk that Steam has since republished.

## Player setup

The launcher ships inside the Storm workshop item:

```
steamapps/workshop/content/108600/<storm id>/mods/storm/launcher/StormLauncher.bat
steamapps/workshop/content/108600/<storm id>/mods/storm/launcher/StormLauncher.sh
steamapps/workshop/content/108600/<storm id>/mods/storm/launcher/storm-launcher.jar
```

Run `StormLauncher.bat` on Windows or `sh StormLauncher.sh` on linux/mac
(both use the game's bundled JRE; Steam does not preserve the execute bit, so
invoke the shell script through `sh`). Add a server, Join. Paths (game dir,
bootstrap dir, JVM) are auto-detected on all three platforms — including the
linux/mac depots that nest the game in a `projectzomboid/` subdirectory and
the macOS JRE bundle layout; override them in Settings if needed. Config
lives at `<home>/Zomboid/storm/launcher/launcher.json`; the launcher log at
`<home>/Zomboid/storm/launcher/logs/launcher.log`.

### Starting from Steam

Make Steam's Play button open the launcher: right-click Project Zomboid →
*Properties…* → *General* → *Launch Options*:

```
"<steam library>\steamapps\workshop\content\108600\<storm id>\mods\storm\launcher\StormLauncher.bat" %command%
```

With `%command%` present, Steam runs the quoted program **instead of** the
game, appending the vanilla game command — which the launcher deliberately
ignores (it builds its own command from the game's json and starts the game
itself when you hit Join). Insert `--join <server>` before `%command%` to skip
the UI and go straight to a saved server. Two caveats: Steam's green "In-Game"
status follows the process it launched, not the game the launcher spawns, and
accepting a Steam invite bypasses Launch Options entirely (see above).

Alternatively, *Add a Non-Steam Game* pointing at `StormLauncher.bat` gives
the launcher its own library entry; the game still authenticates through the
running Steam client either way.

## Server setup (publishing java mods to clients)

Create the client-mods directory on the server host and drop in each mod using
the normal mod layout:

```
~/Zomboid/storm/client-mods/
  my-mod/
    common/
    mod.info            (or 42/mod.info)
    42/
      my-mod.jar
```

Override the directory with `-Dstorm.client.mods.dir=<path>`. The two endpoints
(`/storm/client/manifest`, `/storm/client/file?path=…`) are served by Storm's
HTTP server (`-Dstorm.http.port`) and registered **only on server JVMs**
(`-Dstorm.server=true`). Manifest hashes are cached and invalidated by
size/mtime, so republishing is just replacing files in that directory.

Only publish mods that are safe to hand to clients — everything in
`client-mods/` is downloadable by anyone who can reach the port. Server-side
anti-cheat mods do not belong there.

### Exposing the port

Players need to reach the Storm HTTP port. Prefer fronting it with a reverse
proxy that forwards **only** `/storm/client/*` (the other inspection endpoints
leak player IPs, and the hot-reload endpoints must never be public — they are
off by default; keep them off). A direct firewall opening also works if you
accept exposing the inspection endpoints.

## Headless / scripting

```
java -jar storm-launcher.jar --list
java -jar storm-launcher.jar --sync <name|host:port>
java -jar storm-launcher.jar --print-launch <name|host:port>
java -jar storm-launcher.jar --join <name|host:port>
```

`--print-launch` prints the exact command line (password masked) without
starting anything — useful for debugging launch problems. (It shows the
`+connect` form; a real `--join` with auto-join armed omits those args and
passes `-Dstorm.autojoin.file` instead, because Storm's client Java drives the
connect.) `--steam-update <ids…>` is internal — the child-process mode the
workshop pre-update spawns with cwd = game dir.

## Development

- Build: `./gradlew :launcher:jar` → `launcher/build/libs/storm-launcher.jar`
  (also copied into the workshop layout by `installStorm`).
- Test: `./gradlew :launcher:test`.
- Bootstrap resolution is workshop-first: an installed Storm workshop item
  (the item the launcher jar itself ships inside is tried first, then
  prod/stage/dev) always wins over the local-dev install under
  `~/Zomboid/Workshop/storm/…` — clients run the Storm that Steam downloaded,
  and the join flow keeps it current. The local-dev tree is only used when no
  workshop item is installed (the launcher then adds `-DstormType=local` so
  the bootstrapper finds its libs), or when the bootstrap dir is set
  explicitly in Settings.
- Hard rule reminder: the launcher itself must stay free of PZ classes — it
  only writes files and spawns a process. Anything that needs the game's
  runtime (Lua env, UI, world state) belongs in Storm-core client Java on the
  other side of a system-property or file handoff, like
  `io.pzstorm.storm.client.LauncherAutoJoin`.
