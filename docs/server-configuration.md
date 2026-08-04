# Server Configuration

Storm's server-tunable knobs split into two groups:

- **Bootstrap / dev flags** are set as `-D<key>=<value>` on the JVM command line — they have to
  take effect before the server reaches `OnServerStarted` (bootstrap target, log level, HTTP port,
  hot-reload, Prometheus).
- **Performance knobs** are vanilla **sandbox options** — admins set them in the world's
  `<SaveName>.ini` (or the in-game world setup UI on the "Storm | Performance" tab), and Storm
  reads them at `OnServerStarted` and pushes the values through the live setters.

## Bootstrap / dev system properties

Pass as `-D<key>=<value>` on the JVM command line (or via `JAVA_TOOL_OPTIONS` in a launcher
script). All flags are opt-in unless noted.

| Flag | Purpose |
|------|---------|
| `-Dstorm.server=true` | **Required.** Tells the bootstrap agent it is running on the dedicated-server JVM so it targets `GameServer`. Storm is a server-only framework — this is always set. |
| `-DstormType=local` | Load Storm from `~/Zomboid/Workshop/storm` instead of the Steam workshop path. Local development only. |
| `-DLOG_LEVEL=DEBUG` | Storm log verbosity (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`). Default `INFO`. |
| `-Dstorm.http.port=<port>` | Start Storm's HTTP server on `<port>`. Required for inspection endpoints and developer hot-reload. Conventionally `41798` on the dedicated server. |
| `-Dstorm.hotreload=true` | Register the `/reload` and `/eval` developer endpoints. See [Developer Hot-Reload Endpoints](http-api.md#developer-hot-reload-endpoints). **Local development only.** |
| `-DprometheusPort=<port>` | Start PZ's built-in Prometheus HTTP server on `<port>`. Required to scrape Storm + `pz_*` + `jvm_*` metrics at `/metrics`. (PZ flag — Storm registers into PZ's default registry.) |
| `-DprometheusHost=<host>` | Hostname/IP the server reports for itself in metrics endpoints. Defaults to `GameServer.ip`. (PZ flag.) |
| `-Dstorm.mainloop.timings=true` | Emit a per-tick wall-clock breakdown of `GameServer.main`'s frame-step to a dedicated `<STORM_LOG_DIR>/storm/timings.log` (25 MB cap, one rolled archive). Each line lists every patched step (`ServerMap.preupdate`, `IngameState.update`, `VehicleManager.serverUpdate`, …) sorted by duration, plus `other=` for unmeasured wall-clock. Off by default — leave off in production unless investigating a slow tick. See [Per-tick step timings](server-thread-main-loop.md#7-per-tick-step-timings). |
| `-Dstorm.cells.keepWarm=true` | Keep `IsoCell` state resident in memory past vanilla's unload point to eliminate load/unload thrash on repeated boundary crossings. Read once at class-load time by `StormCellWarmingConfig#isEnabled()`. Off by default — vanilla unload semantics. |
| `-Dstorm.reapStalledConnectionMs=<ms>` | Wall-clock budget a connection gets to finish logging in **and spawn a character** before it is dropped and its RakNet slot freed. Default `600000` (10 minutes) — **on by default**, this is half of the "Getting Server Info..." fix. Applies only to connections that never reached `isFullyConnected()` (that flag flips at character spawn, so this also kicks players parked on the pre-spawn "Click Start" screen); fully-connected players are never touched, and time spent in the login queue / awaiting co-op approval / pending Google auth / **actively downloading chunks** does not count against the budget (each chunk-request wave re-stamps the clock, so a slow link cannot get a client kicked mid-load — which would crash a vanilla client outright). Raise it if your players routinely need longer than 10 minutes of *idle* pre-spawn time. Setting this flag **pins** the budget: the `Storm.ReapStalledConnectionSeconds` sandbox option is ignored while it is present. Omit the flag to make the budget sandbox-tunable at runtime. |
| `-Dstorm.reapSweepIntervalMs=<ms>` | How often the stalled-connection sweep runs on the server main thread. Default `30000`. Only worth changing in tests; detection granularity is one sweep interval on each side of the budget. |
| `-Dstorm.raknet.connectionHeadroom=<n>` | Spare RakNet incoming-connection slots above `MaxPlayers`, for the login pipeline. Default `64`. The resolved cap is `clamp(max(101, MaxPlayers + n), .., 256)`, so the vanilla 101 is a floor (small servers are never regressed) and 256 is a hard ceiling. If RakNet refuses to start with the raised cap, Storm logs an error and automatically retries with the vanilla 101, so a bad cap can never keep the server down. Set `n=0` to force vanilla behavior (rollback lever). Boot verification: the server log must show `Storm: RakNet incoming-connection cap raised 101 -> <cap>` — if that line is missing, the running server is not executing this build. |
| `-Dstorm.raknet.connectionCap=<n>` | Absolute RakNet incoming-connection cap, bypassing the headroom calculation. Still floored at the vanilla 101 and clamped to 256. Use only to pin an exact value; prefer `connectionHeadroom`. |
| `-Dstorm.steam.advertisePipelinePlayers=<bool>` | Default `true` — **on by default**. Makes the Steam-advertised player count (in-game server browser, A2S, BattleMetrics) cover everyone the server is holding: spawned players, every pre-spawn login-pipeline connection (downloading the world, character creation), and players waiting in the login queue (no player id yet — advertised under synthetic ids above the real-id range), clamped at `MaxPlayers` (the vanilla browser silently delists servers advertising above their max). Without it a busy server shows e.g. `85/100` while correctly refusing joiners at `103 >= 100`, or `85/100` while 15 people sit in the login queue — queue admission is serialized, so queues form even below capacity. While active, Storm's per-tick reconciler is the Steam user list's single writer (vanilla's spawn/disconnect/role-toggle `AddPlayer`/`RemovePlayer` calls are suppressed); on any native failure it reverts to vanilla registration for the rest of the run. Steam mode only — non-Steam servers already report the pipeline-inclusive `getPlayerCount()` to the public list. Set `false` to restore vanilla spawned-only advertising (rollback lever; also flippable live via `SteamPlayerListReconciler.setEnabled(false)`). Boot verification: `Storm: Steam advertised player count now covers the login pipeline ...` in the server log. Gauge: `storm_steam_advertised_players`. |

## Sandbox options (performance knobs)

All Storm performance knobs are exposed as sandbox options on the "Storm | Performance" tab in
the world setup UI. Edit them through the admin UI before world creation, or hand-edit
`<SaveName>.ini` between runs. The Storm sandbox applier reads every option on
`OnServerStarted` and pushes it through the corresponding live setter.

| Sandbox option | Default | Range | Effect |
|---|---|---|---|
| `Storm.ServerFps` | `10` | `1..240` | Server FPS. Sets the main-loop tick gate (`intervalMs = round(1000 / fps)`), `PerformanceSettings.getLockFPS()` on the server, and the `IsoPhysicsObject.update()` FPS scalar. `10` = vanilla 10 TPS. Practical ceiling is ~200 real TPS: the vanilla idle path in `GameServer.main` sleeps up to 5 ms between gate checks and `UpdateLimit` has 1 ms granularity, so values above ~200 are quantized down. Random per-tick events routed through `Rand.AdjustForFramerate` (fire spread, wound infection, footstep noise, drunk stumbles, vehicle engine auto-stall since 42.20.0) are covered: Storm rescales the vanilla hardcoded 10-TPS server scalar to the live tick rate (`RandAdjustForFrameratePatch`), keeping per-second event rates constant at any value. |
| `Storm.AnimalLOSTickInterval` | `1` | `0..64` | Per-animal stride for `IsoAnimal.updateLOS()`. `1` = vanilla every tick. Larger = each animal scans LOS every Nth tick (cheaper). `0` disables animal LOS entirely. |
| `Storm.VirtualAnimalTickInterval` | `1` | `0..16` | Stride for the whole `AnimalZones.updateVirtualAnimals()` pass (off-screen animal movement, eat/sleep, track expiry). `1` = vanilla every tick. Larger = the pass runs every Nth tick with `GameTime.perObjectMultiplier` raised to N for the call, so time-delta-driven logic advances the skipped ticks' worth of time in one step — same ground covered, coarser steps (the world-map track overlay looks jumpier at high values). `0` freezes virtual-animal simulation and track expiry entirely; debug/emergency use only. Typical busy-server value `4`. |
| `Storm.ZombieAuthTickInterval` | `1` | `1..16` | Per-zombie stride for the ownership rescan of *unowned* zombies in `NetworkZombieManager.updateAuth()`. Vanilla re-runs the full O(connections × players) relevance scan for every unowned zombie every tick (the 2 s `lastChangeOwner` gate only stamps on actual change, so it never engages while a zombie stays unowned). With N > 1 each unowned zombie rescans every Nth tick, phase-spread by online ID. Owned zombies keep vanilla's 2 s gate untouched. Worst case a newly relevant zombie waits N-1 extra ticks for owner assignment (1.5 s at the ceiling on a 10 TPS server). Typical busy-server value `4`. |
| `Storm.InventoryItemSweepTickInterval` | `1` | `1..64` | Stride for the orphaned-item GC sweep in `InventoryItemSystem.update()` (unregisters item entities whose equip parent is gone or dead). The sweep is idempotent garbage collection, so striding only delays entity cleanup by up to N-1 ticks with no gameplay effect. Typical busy-server value `10`. |
| `Storm.MaxTotalZombies` | `0` | `0..32000` | World-wide ceiling on real zombies. `0` = disabled (vanilla behaviour — vanilla has **no** global cap of any kind; see [below](#zombie-culling-is-a-vanilla-option)). When the live zombie count exceeds the cap, Storm sweeps once per second and deletes up to 200 surplus zombies per sweep, restricted to zombies that satisfy vanilla's own cull predicate widened to every connection: not a reanimated player, no target, outside (no room, no roof), and beyond `(relevantRange - 2) * 10` tiles of *every* player on *every* connection. If too few zombies qualify the world stays over the cap — the sweep never deletes a zombie someone could be looking at. This is a backstop against the per-tick costs that scale with the world total (chiefly `NetworkZombiePacker.updateAuth()`), not a population control: deleted zombies re-enter the native respawn schedule, so a cap below what the population settings want shows up as a permanently non-zero `storm_zombies_total_cap_culled_total` rate — lower `ZombieConfig.PopulationMultiplier` rather than raising the cap. Sweep cadence and per-sweep budget are tunable with `-Dstorm.zombieTotalCap.sweepMs` / `-Dstorm.zombieTotalCap.perSweep`. |
| `Storm.ServerLosThreads` | `1` | `1..16` | Concurrent ServerLOS worker count. `1` = vanilla single-threaded baseline. The helper pool always pre-allocates 15 threads regardless; this only controls how many receive work each tick. Typical busy-server value `4..12`. |
| `Storm.NetDataCapMs` | `90` | `0..200` | Per-outer-loop-spin wall-clock cap on `GameServer.mainLoopDealWithNetData` (HIGH-priority + player-update + vehicle inbound drain combined). When a spin exceeds the cap, subsequent packets in that spin are dropped at the application level (already ACKed — RakNet does not retransmit them; the periodic update streams regenerate the state); the next spin starts fresh. Protects world-tick scheduling and the RakNet outbound send buffer during reconnect storms. `0` disables (vanilla behaviour, no cap). Deferrals counted by `pz_netdata_deferred_total`. |
| `Storm.PeerSendBufferKickMb` | `20` | `0..1000` | Per-peer HIGH-priority RakNet send-buffer threshold (MB) above which Storm force-disconnects the peer after `Storm.PeerSendBufferKickHoldTicks` consecutive ticks. Protects the server from OOM when a peer on a saturated/lossy uplink accumulates the server's broadcast firehose (PZ has no backpressure in the HIGH send paths). `0` disables the watchdog (per-peer telemetry still populates). |
| `Storm.PeerSendBufferKickHoldTicks` | `50` | `1..6000` | Consecutive server ticks a peer's HIGH send buffer must stay above the kick threshold before disconnect fires. At vanilla 10 TPS, 50 ticks = 5 s. Has no effect when `Storm.PeerSendBufferKickMb = 0`. |
| `Storm.ScreenshotPiecesPerPacket` | `4` | `1..28` | Wire framing only: base64 pieces (24573 raw bytes each) packed into a single `sendClientCommand` packet when a client uploads a `/screenshot` back to the server. **Not** the throttle — disconnect safety is governed by `Storm.ScreenshotUploadKbPerSec`. `4` ≈ 131 KB/packet. Hard ceiling 28 (~918 KB/packet, ~82 KB headroom under vanilla `UdpConnection`'s 1 MB outbound buffer); 30+ throws `BufferOverflowException` mid-send. |
| `Storm.ScreenshotUploadKbPerSec` | `128` | `8..4096` | Wall-clock throughput cap (KiB/s of base64 wire bytes) on a client's `/screenshot` upload. **The disconnect fix.** Keeps the upload well under the player's uplink so RakNet ACK/keepalive traffic keeps flowing; without it a large-resolution screenshot builds a reliable-ordered send backlog longer than RakNet's ~10 s connection timeout (never overridden from its native default) and the player is dropped with "Connection Lost" — the backlog scaling with resolution is why large monitors broke. `128` (~1 Mbit) is safe on virtually any home uplink; raise on low-latency wired networks. A 4K capture may take a minute or more at the default, invisibly in the background. |
| `Storm.ScreenshotEncodeKbPerTick` | `4` | `1..64` | Ceiling on source KiB base64-encoded per client tick during a `/screenshot` upload. **The client-lag fix.** Base64 runs on the single Lua main thread, so encoding a whole 24 KB piece per frame stalled rendering for the entire upload; encoding is also demand-driven (pauses once packets are buffered ahead of the throttled sender), so most ticks do little work. Lower = smoother frames; higher = faster encode but larger per-frame hitches. |
| `Storm.ReapStalledConnectionSeconds` | `600` | `60..7200` | Wall-clock budget a connection gets to finish logging in **and spawn** before the stalled-connection reaper drops it and frees its RakNet slot — the sandbox mirror of `-Dstorm.reapStalledConnectionMs` (see [JVM flags](#jvm-flags) for the full exemption list: login queue, co-op approval, Google auth, active chunk download). Live-appliable: an admin sandbox push takes effect on the next sweep, no restart. **Precedence:** when `-Dstorm.reapStalledConnectionMs` is set on the server JVM, the flag always wins and this option is ignored (logged at INFO on every apply). |
| `Storm.ZombieSightVehicleFastPath` | `true` | boolean | Chunk-windowed fast path for the zombie-sight vehicle-occlusion test (`IsoZombie.isVehicleBetween`). Vanilla scans every loaded vehicle (two matrix inversions each) per zombie sight roll; the fast path tests only vehicles near the ≤20-tile sight segment and skips entirely when the result is provably unused. Behavior-preserving; ~14% of main-thread CPU on vehicle-heavy servers. Set `false` to restore the vanilla scan (live-appliable via admin sandbox push). |

The matching `storm_*` Prometheus gauges (`storm_server_tick_interval_seconds`,
`storm_server_lock_fps`, `storm_iso_physics_server_fps`, `storm_animal_los_tick_interval`,
`storm_virtual_animal_tick_interval`, `storm_zombie_auth_tick_interval`,
`storm_inventory_item_sweep_tick_interval`, `storm_max_total_zombies`,
`storm_server_los_threads`, `storm_netdata_cap_ms`,
`storm_peer_send_buffer_kick_mb`, `storm_peer_send_buffer_kick_hold_ticks`,
`storm_screenshot_pieces_per_packet`, `storm_screenshot_upload_kb_per_sec`,
`storm_screenshot_encode_kb_per_tick`, `storm_connection_reap_timeout_seconds`,
`storm_zombie_sight_vehicle_fast_path`) reflect the currently-applied value.

### Zombie culling is a vanilla option

Storm used to ship `Storm.ZombieCullThreshold`. It is **gone** as of the 42.20.0 update — set
vanilla's `ZombieConfig.ZombiesCountBeforeDelete` directly (world setup UI → Zombies, or
`<SaveName>_SandboxVars.lua`). Through 42.19.1 the vanilla option was unusable (whole-map budget,
capped at 500, minimum 10 so culling could not be turned off, and a missing decrement that
mass-deleted ~10% of the population per frame on overshoot), which is why Storm patched around it.
42.20.0 fixed all of that: the budget is the per-connection surplus of the zombie list actually
streamed to each client, the range is now `0..5000`, and `0` means "never cull".

Upgrading from a Storm build that had the option: any `Storm.ZombieCullThreshold` line left in
`<SaveName>_SandboxVars.lua` is inert (unregistered options are never read and are dropped on the
next save) and the server silently reverts to vanilla's default of `300` per connection. If you had
set `0` to disable culling, set `ZombieConfig.ZombiesCountBeforeDelete = 0` to keep that behaviour.
Note the units changed: `500` under 42.19.1 meant 500 zombies **map-wide**, `500` now means 500 per
connection, so a carried-over number is far more permissive than it looks. The
`storm_zombie_cull_threshold` gauge still exists and now reports vanilla's live value.

### There is no vanilla cap on the world-wide zombie total

Every ceiling vanilla ships is local. `ZombieConfig.ZombiesCountBeforeDelete` counts only the zombies
streamed to a **single connection**, and even then only deletes ones that are outside, have no
target, and are beyond `(relevantRange - 2) * 10` tiles from every player on that connection.
`MaxZombiesPerChunk` (255) bounds one 8×8 chunk. The `300` in `NetworkZombiePacker.getZombieData` is
packet framing. Nothing looks at the world total, which is just "loaded chunks around every player ×
population density" and grows without bound as players spread out.

That total is what drives the per-tick costs that scale linearly with it — chiefly
`NetworkZombiePacker.updateAuth()`, which walks the entire zombie list every tick and re-scans every
connection's players for each unowned zombie. Servers typically start degrading somewhere in the
thousands.

Storm's `Storm.MaxTotalZombies` (default `0`, disabled) is the backstop for this. See the table
above. The complementary levers, in the order worth reaching for them:

1. `ZombieConfig.PopulationMultiplier` — fewer zombies generated in the first place. Always the right
   first move if the server is consistently over budget.
2. `Storm.ZombieAuthTickInterval = 4` — divides the dominant per-tick scan cost by 4 without changing
   the population at all.
3. `ZombieConfig.ZombiesCountBeforeDelete` — tightens the per-connection ceiling, which indirectly
   caps the total.
4. `Storm.MaxTotalZombies` — the hard ceiling, for when the above still leaves headroom spikes.

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
    -DprometheusPort=9092 \
    -DprometheusHost=<your-host>"

# Performance knobs (LOS threads, animal-LOS stride, zombie cull, server fps) are set in the
# "Storm | Performance" tab of the world setup UI, or by hand-editing <SaveName>.ini between
# runs. See the sandbox table above for names, defaults, and ranges.

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
