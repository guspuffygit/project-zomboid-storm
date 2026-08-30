package io.pzstorm.storm.connection;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormConnectionStageMetrics;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.znet.SteamGameServer;
import zombie.core.znet.SteamUtils;
import zombie.network.GameServer;
import zombie.network.ServerOptions;

/**
 * Owns the Steam game-server user list so the advertised player count covers everyone the server is
 * currently holding — spawned players, the post-login pipeline, and the login queue — instead of
 * only spawned characters.
 *
 * <p>The two numbers vanilla shows a joining player disagree by construction. The login gate
 * ({@code LoginPacket.processServer}, and {@code LoginQueue.getCountPlayers()} when the queue is
 * enabled) compares {@code GameServer.getPlayerCount()} against {@code MaxPlayers} — and {@code
 * getPlayerCount()} counts every connection whose {@code playerIds} slot is assigned, which happens
 * at login grant ({@code receiveClientConnect}), long before spawn. The advertised count is the
 * Steam user list, which vanilla feeds only at character spawn ({@code receivePlayerConnect} →
 * {@code SteamGameServer.AddPlayer}) and drains on disconnect. On a busy server the login pipeline
 * (queued, downloading the world, sitting on character creation) holds 10-20 connections, so the
 * browser and every A2S consumer show e.g. 85/100 while the gate correctly refuses at 103 ≥ 100 —
 * and players report "the server says full but it isn't".
 *
 * <p><b>Entry ids are Storm-allocated table slots, not PZ player ids.</b> The native user table in
 * {@code ZNetJNI64.dll} has exactly {@value #NATIVE_TABLE_SIZE} entries indexed by the id argument;
 * {@code AddPlayer} / {@code RemovePlayer} / {@code UpdatePlayer} silently skip any id outside
 * {@code [0, 512)} (they log {@code "AddPlayer: player-id=%d, active=%d, skip"} and return —
 * verified by disassembly). Real player ids ({@code slot * 4 + playerIndex}, {@code slot} up to the
 * RakNet cap) range up to 1019, so they cannot key the table: any player granted a connection slot
 * ≥ 128 would be dropped from the list, and queue waiters have no player id at all. Since the
 * reconciler is the single writer, the id is nothing but a table key — {@link
 * SteamEntrySlotAllocator} assigns each user a stable slot in {@code [0, 512)} keyed by identity
 * (steamId; fallback lowercase username, then RakNet GUID), held from first sight in the login
 * queue through spawn to disconnect. A2S exposes names, not ids, and the slot never changes
 * mid-session, so external query consumers see one continuous session.
 *
 * <p>Each server tick this reconciler diffs the Steam user list against everyone holding a slot:
 * spawned players first, then pre-spawn pipeline connections (assigned {@code playerIds} entry —
 * the population {@code getPlayerCount()} counts), then post-login connections with no player id
 * yet — players waiting in the login queue, and the one connection the queue has admitted but not
 * granted. One entry per connection, deduped across passes by identity, so a stale half-open
 * connection and the same user's fresh reconnect resolve to a single advertised entry, priority
 * spawned &gt; pre-spawn &gt; waiting. The queue pass matters even below capacity: admission is
 * serialized through {@code currentLoginQueue}, so a joiner burst queues up while the browser would
 * otherwise read "85/100 yet I'm 15th in line". Everything is truncated at {@code
 * ServerOptions.getMaxPlayers()}. The clamp is not cosmetic: the vanilla in-game browser silently
 * delists any server advertising {@code players > maxPlayers} ({@code MultiplayerUI.lua}'s
 * anti-spam filter), so a full pipeline reads as exactly {@code MaxPlayers/MaxPlayers} — never
 * above.
 *
 * <p><b>Single writer.</b> While the reconciler is active, {@code SteamGameServerPlayerListPatch}
 * suppresses the vanilla {@code AddPlayer(IsoPlayer)} / {@code RemovePlayer(IsoPlayer)} / {@code
 * UpdatePlayer(IsoPlayer)} wrapper bodies (spawn, disconnect, role-visibility toggles, zombie-kill
 * score pushes). The first two because the native list's duplicate-id behavior makes concurrent
 * writers unsafe; {@code UpdatePlayer} because it passes the <em>real</em> player id, and with
 * Storm-allocated slots the native would write the score onto whichever user Storm registered at
 * that slot number (the native updates any <em>active</em> entry at the given id — verified by
 * disassembly). The sweep re-derives everything those call sites express — spawn, disconnect,
 * {@code HideFromSteamUserList} toggles, score changes — one tick later at most.
 *
 * <p><b>Failure reverts to vanilla.</b> The natives are reached by reflection ({@code AddPlayer /
 * RemovePlayer / UpdatePlayer(short, ...)} are private); if resolution or any native call ever
 * fails, the reconciler logs, best-effort removes every entry it registered (vanilla cannot remove
 * them — it only knows real player ids), stops suppressing the wrappers, and stays off for the rest
 * of the run. A clean runtime disable additionally re-registers currently spawned players under
 * their vanilla ids so the handed-back list is exactly what vanilla's disconnect path expects.
 *
 * <p>All sweeping runs on the server main thread (from {@code ServerTickAdvice}) — {@code
 * UdpEngine.connections} and the Steam natives are main-thread state. {@link #setEnabled(boolean)}
 * may be called from another thread (HTTP eval); it only flips a flag, and the handover work
 * happens on the next main-thread sweep.
 *
 * <p>Non-Steam servers need none of this: {@code PublicServerUtil} already reports {@code
 * getPlayerCount()} to the public server list, so the reconciler is inert without Steam mode.
 *
 * <p>Rollback lever: {@code -Dstorm.steam.advertisePipelinePlayers=false}.
 */
public final class SteamPlayerListReconciler {

    public static final String ENABLED_PROPERTY = "storm.steam.advertisePipelinePlayers";

    /**
     * Size of the native user table in {@code ZNetJNI64.dll}: {@code AddPlayer} / {@code
     * RemovePlayer} / {@code UpdatePlayer} all guard {@code id >= 0x200} (and {@code id < 0}) with
     * a silent skip, and index a 0x38-byte-stride entry table with the id. No id outside {@code [0,
     * 512)} may ever reach the natives — the call would be dropped while Storm's shadow counted it,
     * over-reporting {@code storm_steam_advertised_players} relative to what Steam actually shows.
     */
    static final int NATIVE_TABLE_SIZE = 512;

    private static final String PRE_SPAWN_FALLBACK_NAME = "(connecting)";

    /** Shadow of the native list: name per registered slot, {@code null} = not registered. */
    private static final String[] registeredNames = new String[NATIVE_TABLE_SIZE];

    private static final int[] registeredScores = new int[NATIVE_TABLE_SIZE];

    private static final String[] desiredNames = new String[NATIVE_TABLE_SIZE];
    private static final int[] desiredScores = new int[NATIVE_TABLE_SIZE];
    private static final int[] desiredStamp = new int[NATIVE_TABLE_SIZE];
    private static final short[] desiredIds = new short[NATIVE_TABLE_SIZE];

    private static final SteamEntrySlotAllocator slots =
            new SteamEntrySlotAllocator(NATIVE_TABLE_SIZE);

    private static final Method NATIVE_ADD;
    private static final Method NATIVE_REMOVE;
    private static final Method NATIVE_UPDATE;
    private static final boolean REFLECTION_AVAILABLE;

    private static volatile boolean enabled =
            !"false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY, "true"));
    private static volatile boolean broken;

    private static int registeredCount;
    private static int sweepCounter;
    private static boolean announcedActive;
    private static boolean announcedDisabled;

    /**
     * Whether the reconciler currently owns the native list. False at boot and after a runtime
     * disable has handed the list back to vanilla; the first active sweep after that must evict
     * vanilla's real-id entries before writing Storm-slot entries, or the same player would be
     * counted twice (once under each id).
     */
    private static boolean ownsList;

    static {
        Method add = null;
        Method remove = null;
        Method update = null;
        boolean available = false;
        try {
            add =
                    SteamGameServer.class.getDeclaredMethod(
                            "AddPlayer", short.class, String.class, int.class);
            remove = SteamGameServer.class.getDeclaredMethod("RemovePlayer", short.class);
            update =
                    SteamGameServer.class.getDeclaredMethod("UpdatePlayer", short.class, int.class);
            add.setAccessible(true);
            remove.setAccessible(true);
            update.setAccessible(true);
            available = true;
        } catch (Throwable t) {
            LOGGER.error(
                    "Storm: SteamGameServer player-list natives are not reachable — the advertised"
                            + " player count stays vanilla (spawned players only)",
                    t);
        }
        NATIVE_ADD = add;
        NATIVE_REMOVE = remove;
        NATIVE_UPDATE = update;
        REFLECTION_AVAILABLE = available;
    }

    private SteamPlayerListReconciler() {}

    /**
     * Whether the vanilla {@code AddPlayer(IsoPlayer)} / {@code RemovePlayer(IsoPlayer)} / {@code
     * UpdatePlayer(IsoPlayer)} wrapper bodies should be skipped. Stays true after a runtime disable
     * until the next sweep has handed the list back, so vanilla and the reconciler never write
     * concurrently.
     */
    public static boolean suppressVanillaWrites() {
        if (broken || !REFLECTION_AVAILABLE) {
            return false;
        }
        return enabled || registeredCount > 0;
    }

    /**
     * Runtime kill switch (safe from any thread — flag only, the main-thread sweep does the
     * handover). Disabling removes Storm's entries, re-registers spawned players under their
     * vanilla ids, and returns list ownership to the vanilla spawn/disconnect calls.
     */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Entries currently registered with the Steam game server. Main-thread value. */
    public static int getRegisteredCount() {
        return registeredCount;
    }

    /**
     * Reconciles the Steam user list with the login-gate population. Called once per server tick
     * from {@code ServerTickAdvice}; must run on the server main thread.
     */
    public static void sweep() {
        if (!GameServer.server || !REFLECTION_AVAILABLE || broken) {
            return;
        }
        if (!enabled) {
            handleDisabled();
            return;
        }
        if (!SteamUtils.isSteamModeEnabled()) {
            return;
        }
        UdpEngine engine = GameServer.udpEngine;
        if (engine == null) {
            return;
        }
        if (!ownsList) {
            if (!removeSpawnedVanillaEntries(engine)) {
                return;
            }
            ownsList = true;
        }

        int maxPlayers = ServerOptions.getInstance().getMaxPlayers();
        int stamp = ++sweepCounter;
        HashSet<String> present = new HashSet<>();
        int desiredCount = gatherDesired(engine, maxPlayers, stamp, present);

        // Removals before adds, so the native list never exceeds MaxPlayers mid-sweep when
        // membership rotates at the clamp.
        for (int slot = 0; slot < NATIVE_TABLE_SIZE; slot++) {
            if (registeredNames[slot] != null && desiredStamp[slot] != stamp) {
                if (!nativeRemove((short) slot)) {
                    return;
                }
                registeredNames[slot] = null;
                registeredCount--;
            }
        }

        for (int k = 0; k < desiredCount; k++) {
            short slot = desiredIds[k];
            String name = desiredNames[slot];
            int score = desiredScores[slot];
            String current = registeredNames[slot];
            if (current == null) {
                if (!nativeAdd(slot, name, score)) {
                    return;
                }
                registeredCount++;
            } else if (!current.equals(name)) {
                // Same slot, different user: the slot was reassigned between sweeps.
                if (!nativeRemove(slot) || !nativeAdd(slot, name, score)) {
                    return;
                }
            } else if (score != registeredScores[slot]) {
                if (!nativeUpdate(slot, score)) {
                    return;
                }
            }
            registeredNames[slot] = name;
            registeredScores[slot] = score;
        }

        // Prune after the removal phase has drained departed identities' native entries, so a
        // freed slot is never re-handed while still registered under the old name mid-sweep.
        slots.retainAll(present);

        StormConnectionStageMetrics.setSteamAdvertisedPlayers(registeredCount);
        if (!announcedActive) {
            announcedActive = true;
            LOGGER.info(
                    "Storm: Steam advertised player count now covers the login pipeline (spawned"
                            + " players first, then connecting, then login-queue waiters, clamped"
                            + " at MaxPlayers={}) — entries keyed by Storm-allocated table slots,"
                            + " vanilla AddPlayer/RemovePlayer/UpdatePlayer suppressed",
                    maxPlayers);
        }
    }

    /**
     * Fills the desired arrays, one entry per visible connection, in three priority passes so
     * earlier classes always survive the {@code maxPlayers} truncation: spawned players, then
     * pre-spawn pipeline connections with an assigned player id (together, the population {@code
     * GameServer.getPlayerCount()} counts), then post-login connections with no player id yet —
     * players waiting in the login queue, plus the one the queue has admitted but {@code
     * receiveClientConnect} has not granted. {@code LoginPacket} sets username and role before any
     * queueing, so waiting entries carry real usernames; connections that never sent a login have
     * no username and are not advertised, matching every vanilla gate.
     *
     * <p>Every classified identity is recorded in {@code present} — including past the clamp — so
     * slot assignments stay stable while an entry is truncated out. Duplicate connections for the
     * same identity (stale half-open + fresh reconnect) collapse onto one slot: the second claim
     * finds the slot already stamped this sweep and is skipped.
     */
    private static int gatherDesired(
            UdpEngine engine, int maxPlayers, int stamp, HashSet<String> present) {
        int desiredCount = 0;
        List<UdpConnection> connections = engine.connections;
        for (int pass = 0; pass < 3; pass++) {
            for (int n = 0; n < connections.size(); n++) {
                UdpConnection connection = connections.get(n);
                if (connection == null || !isVisible(connection)) {
                    continue;
                }
                int assignedIndex = -1;
                int spawnedIndex = -1;
                for (int i = 0; i < 4; i++) {
                    if (connection.playerIds[i] < 0) {
                        continue;
                    }
                    if (assignedIndex < 0) {
                        assignedIndex = i;
                    }
                    if (connection.players[i] != null) {
                        spawnedIndex = i;
                        break;
                    }
                }
                IsoPlayer player = null;
                String name;
                if (pass == 0) {
                    if (spawnedIndex < 0) {
                        continue;
                    }
                    player = connection.players[spawnedIndex];
                    name = entryName(connection, player, spawnedIndex);
                } else if (pass == 1) {
                    if (spawnedIndex >= 0 || assignedIndex < 0) {
                        continue;
                    }
                    name = entryName(connection, null, assignedIndex);
                } else {
                    if (assignedIndex >= 0 || connection.getUserName() == null) {
                        continue;
                    }
                    name = connection.getUserName();
                }
                String identity = identityOf(connection);
                present.add(identity);
                if (desiredCount >= maxPlayers) {
                    continue;
                }
                short slot = slots.acquire(identity);
                if (slot < 0 || desiredStamp[slot] == stamp) {
                    continue;
                }
                desiredStamp[slot] = stamp;
                desiredNames[slot] = name;
                desiredScores[slot] = player != null ? player.getZombieKills() : 0;
                desiredIds[desiredCount++] = slot;
            }
        }
        return desiredCount;
    }

    /**
     * Stable identity key for slot assignment and cross-pass dedup: steamId when known, else
     * lowercase username (set at login), else the RakNet GUID (pre-login, never absent).
     */
    private static String identityOf(UdpConnection connection) {
        long steamId = connection.getSteamId();
        if (steamId != 0L) {
            return "s:" + steamId;
        }
        String name = connection.getUserName();
        if (name != null) {
            return "u:" + name.toLowerCase();
        }
        return "g:" + connection.getConnectedGUID();
    }

    private static boolean isVisible(UdpConnection connection) {
        Role role = connection.getRole();
        return role != null && !role.hasCapability(Capability.HideFromSteamUserList);
    }

    /** Never returns null — a null name into the native is a JNI crash risk. */
    private static String entryName(UdpConnection connection, IsoPlayer player, int playerIndex) {
        if (player != null && player.getUsername() != null) {
            return player.getUsername();
        }
        if (connection.usernames[playerIndex] != null) {
            return connection.usernames[playerIndex];
        }
        String userName = connection.getUserName();
        return userName != null ? userName : PRE_SPAWN_FALLBACK_NAME;
    }

    /** Runtime disable: hand the list back to vanilla, once, on the main thread. */
    private static void handleDisabled() {
        if (registeredCount > 0) {
            announcedDisabled = true;
            releaseAllEntries();
            reAddSpawnedUnderVanillaIds();
            ownsList = false;
            LOGGER.info(
                    "Storm: Steam pipeline player advertising disabled at runtime — Storm entries"
                            + " removed, spawned players re-registered under vanilla ids and handed"
                            + " back to vanilla registration");
        } else if (!announcedDisabled) {
            announcedDisabled = true;
            LOGGER.info(
                    "Storm: Steam pipeline player advertising disabled by -D{}=false — the"
                            + " advertised player count stays vanilla (spawned players only)",
                    ENABLED_PROPERTY);
        }
    }

    /**
     * Takeover on the inactive→active transition: evicts the real-id entries vanilla registered
     * while it owned the list (spawn-time adds during boot-before-first-sweep or a disabled
     * window), so the same player is never counted under both a vanilla id and a Storm slot.
     * Removing an id the native never registered is a safe skip, so this iterates all spawned
     * players without visibility filtering.
     */
    private static boolean removeSpawnedVanillaEntries(UdpEngine engine) {
        List<UdpConnection> connections = engine.connections;
        for (int n = 0; n < connections.size(); n++) {
            UdpConnection connection = connections.get(n);
            if (connection == null) {
                continue;
            }
            for (int i = 0; i < 4; i++) {
                IsoPlayer player = connection.players[i];
                if (player == null) {
                    continue;
                }
                short id = player.getOnlineID();
                if (id < 0 || id >= NATIVE_TABLE_SIZE) {
                    continue;
                }
                if (!nativeRemove(id)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Clean-disable handover: registers every visible spawned player under its vanilla id ({@code
     * IsoPlayer.getOnlineID()}, exactly what the unsuppressed wrappers pass from here on), so
     * vanilla's disconnect path finds the entries it expects. Ids outside the native table are
     * skipped — the native would silently drop them anyway (vanilla's own blind spot above
     * connection slot 127).
     */
    private static void reAddSpawnedUnderVanillaIds() {
        UdpEngine engine = GameServer.udpEngine;
        if (engine == null || broken) {
            return;
        }
        List<UdpConnection> connections = engine.connections;
        for (int n = 0; n < connections.size(); n++) {
            UdpConnection connection = connections.get(n);
            if (connection == null || !isVisible(connection)) {
                continue;
            }
            for (int i = 0; i < 4; i++) {
                IsoPlayer player = connection.players[i];
                if (player == null) {
                    continue;
                }
                short id = player.getOnlineID();
                if (id < 0 || id >= NATIVE_TABLE_SIZE) {
                    continue;
                }
                if (!nativeAdd(id, entryName(connection, player, i), player.getZombieKills())) {
                    return;
                }
            }
        }
    }

    private static boolean nativeAdd(short id, String name, int score) {
        try {
            NATIVE_ADD.invoke(null, id, name, score);
            return true;
        } catch (Throwable t) {
            markBroken("AddPlayer", t);
            return false;
        }
    }

    private static boolean nativeRemove(short id) {
        try {
            NATIVE_REMOVE.invoke(null, id);
            return true;
        } catch (Throwable t) {
            markBroken("RemovePlayer", t);
            return false;
        }
    }

    private static boolean nativeUpdate(short id, int score) {
        try {
            NATIVE_UPDATE.invoke(null, id, score);
            return true;
        } catch (Throwable t) {
            markBroken("UpdatePlayer", t);
            return false;
        }
    }

    private static void markBroken(String nativeName, Throwable t) {
        broken = true;
        LOGGER.error(
                "Storm: SteamGameServer.{} failed — reverting the advertised player count to"
                        + " vanilla (spawned players only) for the rest of this run",
                nativeName,
                t);
        releaseAllEntries();
    }

    /**
     * Best-effort removes every entry Storm registered — vanilla cannot clean them up, its
     * disconnect path only knows real player ids — and forgets the shadow and all slot assignments.
     */
    private static void releaseAllEntries() {
        for (int slot = 0; slot < NATIVE_TABLE_SIZE; slot++) {
            if (registeredNames[slot] == null) {
                continue;
            }
            try {
                NATIVE_REMOVE.invoke(null, (short) slot);
            } catch (Throwable t) {
                // Best effort — the native already failed once when this runs from markBroken.
            }
            registeredNames[slot] = null;
        }
        registeredCount = 0;
        slots.clear();
        StormConnectionStageMetrics.setSteamAdvertisedPlayers(0);
    }
}
