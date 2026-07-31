package io.pzstorm.storm.connection;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormConnectionStageMetrics;
import java.lang.reflect.Method;
import java.util.HashMap;
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
 * <p>Each server tick this reconciler diffs the Steam user list against everyone holding a slot:
 * spawned players first, then pre-spawn pipeline connections (assigned {@code playerIds} entry —
 * the population {@code getPlayerCount()} counts), then post-login connections with no player id
 * yet — players waiting in the login queue, and the one connection the queue has admitted but not
 * granted. The queue pass matters even below capacity: admission is serialized through {@code
 * currentLoginQueue}, so a joiner burst queues up while the browser would otherwise read "85/100
 * yet I'm 15th in line". Queue-waiters have no player id to key a Steam entry by, so they are
 * registered under synthetic ids allocated downward from {@code MAX_IDS - 1} — a range real ids
 * ({@code slot * 4 + playerIndex}, {@code slot} below the RakNet cap) can never reach — and swap to
 * the real id under the same username at login grant. A2S exposes names, not ids, so query
 * consumers (BattleMetrics and friends) see one continuous session across the swap. Everything is
 * truncated at {@code ServerOptions.getMaxPlayers()}. The clamp is not cosmetic: the vanilla
 * in-game browser silently delists any server advertising {@code players > maxPlayers} ({@code
 * MultiplayerUI.lua}'s anti-spam filter), so a full pipeline reads as exactly {@code
 * MaxPlayers/MaxPlayers} — never above.
 *
 * <p><b>Single writer.</b> While the reconciler is active, {@code SteamGameServerPlayerListPatch}
 * suppresses the vanilla {@code AddPlayer(IsoPlayer)} / {@code RemovePlayer(IsoPlayer)} wrapper
 * bodies (spawn, disconnect, and role-visibility toggles) — the native list's duplicate-id behavior
 * is unknowable from Java, so exactly one writer may exist. {@code UpdatePlayer(IsoPlayer)}
 * (zombie-kill score) is left alone: it only touches ids that are already registered. Role
 * visibility and disconnects converge on the next sweep instead of at the vanilla call sites, one
 * tick later at most.
 *
 * <p><b>Failure reverts to vanilla.</b> The natives are reached by reflection ({@code AddPlayer /
 * RemovePlayer / UpdatePlayer(short, ...)} are private); if resolution or any native call ever
 * fails, the reconciler logs, best-effort removes the pre-spawn entries only it knows about (the
 * spawned ones are handed back to vanilla, whose disconnect path removes them), stops suppressing
 * the wrappers, and stays off for the rest of the run.
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
     * Player ids are {@code slot * 4 + playerIndex} with {@code slot} bounded by the RakNet cap
     * (256, see {@link RakNetConnectionCapConfig#MAX_CAP}), so ids never reach 1024. Real ids stay
     * below {@code cap * 4}; the ids from there up to 1023 are free for {@link #waitingEntryId}'s
     * synthetic queue entries (empty only when the cap is pinned at the full 256).
     */
    private static final int MAX_IDS = 1024;

    private static final String PRE_SPAWN_FALLBACK_NAME = "(connecting)";

    /** Shadow of the native list: name per registered id, {@code null} = not registered. */
    private static final String[] registeredNames = new String[MAX_IDS];

    private static final int[] registeredScores = new int[MAX_IDS];
    private static final boolean[] registeredSpawned = new boolean[MAX_IDS];

    private static final String[] desiredNames = new String[MAX_IDS];
    private static final int[] desiredScores = new int[MAX_IDS];
    private static final boolean[] desiredSpawned = new boolean[MAX_IDS];
    private static final int[] desiredStamp = new int[MAX_IDS];
    private static final short[] desiredIds = new short[MAX_IDS];

    /**
     * Synthetic-id assignments for post-login connections that have no {@code playerIds} entry yet
     * (waiting in the login queue, or admitted but not granted), keyed by RakNet GUID. An
     * assignment is stable while the connection waits — including while clamped out at {@code
     * MaxPlayers} — so entries never churn ids between sweeps.
     */
    private static final HashMap<Long, Short> waitingEntryIds = new HashMap<>();

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
     * Whether the vanilla {@code AddPlayer(IsoPlayer)} / {@code RemovePlayer(IsoPlayer)} wrapper
     * bodies should be skipped. Stays true after a runtime disable until the next sweep has handed
     * the list back, so vanilla and the reconciler never write concurrently.
     */
    public static boolean suppressVanillaWrites() {
        if (broken || !REFLECTION_AVAILABLE) {
            return false;
        }
        return enabled || registeredCount > 0;
    }

    /**
     * Runtime kill switch (safe from any thread — flag only, the main-thread sweep does the
     * handover). Disabling removes Storm's pre-spawn entries and returns list ownership to the
     * vanilla spawn/disconnect calls.
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

        int maxPlayers = ServerOptions.getInstance().getMaxPlayers();
        int stamp = ++sweepCounter;
        int desiredCount = gatherDesired(engine, maxPlayers, stamp);

        // Removals before adds, so the native list never exceeds MaxPlayers mid-sweep when
        // membership rotates at the clamp.
        for (int id = 0; id < MAX_IDS; id++) {
            if (registeredNames[id] != null && desiredStamp[id] != stamp) {
                if (!nativeRemove((short) id)) {
                    return;
                }
                registeredNames[id] = null;
                registeredCount--;
            }
        }

        for (int k = 0; k < desiredCount; k++) {
            short id = desiredIds[k];
            String name = desiredNames[id];
            int score = desiredScores[id];
            String current = registeredNames[id];
            if (current == null) {
                if (!nativeAdd(id, name, score)) {
                    return;
                }
                registeredCount++;
            } else if (!current.equals(name)) {
                // Same id, different user: the slot was reused between sweeps.
                if (!nativeRemove(id) || !nativeAdd(id, name, score)) {
                    return;
                }
            } else if (score != registeredScores[id]) {
                if (!nativeUpdate(id, score)) {
                    return;
                }
            }
            registeredNames[id] = name;
            registeredScores[id] = score;
            registeredSpawned[id] = desiredSpawned[id];
        }

        StormConnectionStageMetrics.setSteamAdvertisedPlayers(registeredCount);
        if (!announcedActive) {
            announcedActive = true;
            LOGGER.info(
                    "Storm: Steam advertised player count now covers the login pipeline (spawned"
                            + " players first, then connecting, then login-queue waiters, clamped"
                            + " at MaxPlayers={}) — vanilla spawn-time AddPlayer/RemovePlayer"
                            + " suppressed",
                    maxPlayers);
        }
    }

    /**
     * Fills the desired arrays: role-visible connections with an assigned player id (the population
     * {@code GameServer.getPlayerCount()} counts) in two passes so spawned players always survive
     * the {@code maxPlayers} truncation ahead of pre-spawn connections, then post-login connections
     * still waiting for a player id (the login queue) last.
     */
    private static int gatherDesired(UdpEngine engine, int maxPlayers, int stamp) {
        int desiredCount = 0;
        List<UdpConnection> connections = engine.connections;
        for (int pass = 0; pass < 2 && desiredCount < maxPlayers; pass++) {
            boolean wantSpawned = pass == 0;
            for (int n = 0; n < connections.size() && desiredCount < maxPlayers; n++) {
                UdpConnection connection = connections.get(n);
                if (connection == null || !isVisible(connection)) {
                    continue;
                }
                for (int i = 0; i < 4 && desiredCount < maxPlayers; i++) {
                    short id = connection.playerIds[i];
                    if (id < 0 || id >= MAX_IDS || desiredStamp[id] == stamp) {
                        continue;
                    }
                    IsoPlayer player = connection.players[i];
                    if (wantSpawned != (player != null)) {
                        continue;
                    }
                    desiredStamp[id] = stamp;
                    desiredNames[id] = entryName(connection, player, i);
                    desiredScores[id] = player != null ? player.getZombieKills() : 0;
                    desiredSpawned[id] = player != null;
                    desiredIds[desiredCount++] = id;
                }
            }
        }
        return gatherWaiting(engine, maxPlayers, stamp, desiredCount);
    }

    /**
     * Third pass: post-login connections with no player id yet — players waiting in the login
     * queue, plus the one the queue has admitted but {@code receiveClientConnect} has not granted.
     * {@code LoginPacket} sets username and role before any queueing, so these entries carry real
     * usernames; connections that never sent a login have no username and are not advertised,
     * matching every vanilla gate. Without this pass a joiner burst reads e.g. 85/100 while 15
     * people sit in queue, because queue admission is serialized even below capacity.
     */
    private static int gatherWaiting(
            UdpEngine engine, int maxPlayers, int stamp, int desiredCount) {
        List<UdpConnection> connections = engine.connections;
        int idFloor = engine.getMaxConnections() * 4;
        HashSet<Long> waiting = null;
        for (int n = 0; n < connections.size(); n++) {
            UdpConnection connection = connections.get(n);
            if (connection == null
                    || !isVisible(connection)
                    || connection.getUserName() == null
                    || hasAssignedPlayerId(connection)) {
                continue;
            }
            if (waiting == null) {
                waiting = new HashSet<>();
            }
            // Track eligibility past the clamp so an assigned id survives membership rotation.
            waiting.add(connection.getConnectedGUID());
            if (desiredCount >= maxPlayers) {
                continue;
            }
            short id = waitingEntryId(connection.getConnectedGUID(), idFloor, stamp);
            if (id < 0) {
                continue;
            }
            desiredStamp[id] = stamp;
            desiredNames[id] = connection.getUserName();
            desiredScores[id] = 0;
            desiredSpawned[id] = false;
            desiredIds[desiredCount++] = id;
        }
        if (!waitingEntryIds.isEmpty()) {
            if (waiting == null) {
                waitingEntryIds.clear();
            } else {
                waitingEntryIds.keySet().retainAll(waiting);
            }
        }
        return desiredCount;
    }

    private static boolean hasAssignedPlayerId(UdpConnection connection) {
        for (int i = 0; i < 4; i++) {
            if (connection.playerIds[i] >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the stable synthetic id for a waiting connection, allocating the highest free one on
     * first sight. {@code -1} when the synthetic range is exhausted — or empty, which happens only
     * with the RakNet cap pinned at the full 256 ({@code 256 * 4 == MAX_IDS}); the waiter is then
     * simply not advertised until its real id arrives.
     */
    private static short waitingEntryId(long guid, int idFloor, int stamp) {
        Short existing = waitingEntryIds.get(guid);
        if (existing != null) {
            return existing;
        }
        for (int id = MAX_IDS - 1; id >= idFloor; id--) {
            if (registeredNames[id] == null
                    && desiredStamp[id] != stamp
                    && !waitingEntryIds.containsValue((short) id)) {
                waitingEntryIds.put(guid, (short) id);
                return (short) id;
            }
        }
        return -1;
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
            releasePreSpawnEntries();
            LOGGER.info(
                    "Storm: Steam pipeline player advertising disabled at runtime — pre-spawn"
                            + " entries removed, spawned players handed back to vanilla"
                            + " registration");
        } else if (!announcedDisabled) {
            announcedDisabled = true;
            LOGGER.info(
                    "Storm: Steam pipeline player advertising disabled by -D{}=false — the"
                            + " advertised player count stays vanilla (spawned players only)",
                    ENABLED_PROPERTY);
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
        releasePreSpawnEntries();
    }

    /**
     * Drops the entries vanilla does not know how to clean up (pre-spawn, no {@code IsoPlayer}
     * behind them) and forgets the shadow. Spawned entries stay registered: vanilla's disconnect
     * path removes them by the same id.
     */
    private static void releasePreSpawnEntries() {
        for (int id = 0; id < MAX_IDS; id++) {
            if (registeredNames[id] == null) {
                continue;
            }
            if (!registeredSpawned[id]) {
                try {
                    NATIVE_REMOVE.invoke(null, (short) id);
                } catch (Throwable t) {
                    // Best effort — the native already failed once when this runs from markBroken.
                }
            }
            registeredNames[id] = null;
        }
        registeredCount = 0;
        waitingEntryIds.clear();
        StormConnectionStageMetrics.setSteamAdvertisedPlayers(0);
    }
}
