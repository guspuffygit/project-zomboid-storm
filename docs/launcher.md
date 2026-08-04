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
   joining. See [Workshop query over UDP](#workshop-query-over-udp).
3. **Steam workshop pre-update** — the server publishes its required workshop
   item ids in the manifest (`workshopItems`, from `server.ini`). Before
   launching, the launcher asks the running Steam client to subscribe + update
   each item via Steam's own UGC API (`steam_api64.dll` bound through
   `java.lang.foreign` — no SDK, no writes into steamapps; Steam manages its
   own content dir). Items are brought to the exact state the game's join gate
   demands (`Subscribed|Installed`, no update pending), so the in-game
   "workshop items" download screen never appears and joins don't wedge on
   locked files. Runs in a child JVM with cwd = game dir (where
   `steam_appid.txt` lives); Steam not running degrades gracefully to the
   vanilla in-game flow.
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
   pre-filled popup flow.
6. **Game launch** — builds the client java command from the game's own
   `ProjectZomboid64.json` (mainClass, classpath, vmArgs, windows overlays),
   appends the Storm agent flags, `-Dstorm.launcher.mods=<synced dir>`, any
   user JVM args, and — unless the auto-join handoff is armed — the vanilla
   `+connect host:port` (`+password <serverPassword>`) args, then spawns the
   JVM with the game directory as working directory. Game output is captured
   to `<home>/Zomboid/storm/launcher/logs/game.log`.

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

## Player setup

The launcher ships inside the Storm workshop item:

```
steamapps/workshop/content/108600/<storm id>/mods/storm/launcher/StormLauncher.bat
steamapps/workshop/content/108600/<storm id>/mods/storm/launcher/storm-launcher.jar
```

Run `StormLauncher.bat` (it uses the game's bundled JRE), add a server, Join.
Paths (game dir, bootstrap dir, JVM) are auto-detected; override them in
Settings if needed. Config lives at
`<home>/Zomboid/storm/launcher/launcher.json`; the launcher log at
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
- The launcher's dir-detection understands the local-dev install: if the Storm
  bootstrap is found under `~/Zomboid/Workshop/storm/…` it automatically adds
  `-DstormType=local` to the game JVM.
- Hard rule reminder: the launcher itself must stay free of PZ classes — it
  only writes files and spawns a process. Anything that needs the game's
  runtime (Lua env, UI, world state) belongs in Storm-core client Java on the
  other side of a system-property or file handoff, like
  `io.pzstorm.storm.client.LauncherAutoJoin`.
