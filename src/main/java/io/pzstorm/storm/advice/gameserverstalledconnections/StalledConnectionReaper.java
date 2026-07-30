package io.pzstorm.storm.advice.gameserverstalledconnections;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.connection.ConnectionStage;
import io.pzstorm.storm.metrics.StormConnectionStageMetrics;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.network.ConnectionManager;
import zombie.network.GameServer;
import zombie.network.LoginQueue;

/**
 * Reaps connections that occupy a RakNet slot without completing the login pipeline within {@link
 * #getConnectTimeoutMs()} of first being seen — wall-clock, not idle time. That covers both halves
 * of the slot-leak problem:
 *
 * <ul>
 *   <li><b>Dead handshakes.</b> A client that sent its username and then died anywhere later in the
 *       connect pipeline (workshop init, mod check, chunk download, character creation) holds its
 *       slot forever. Vanilla's only reap ({@code GameServer.main}'s {@code updateDBCount} block)
 *       is gated on {@code getUserName() == null}, so it never fires for these; RakNet keepalive
 *       does not help because the peer stays responsive while the game-level handshake is dead.
 *       Wall-clock also catches the sneakier variant where the dead peer keeps ACKing pings — an
 *       idle-based rule would spare it forever.
 *   <li><b>"Click Start" campers.</b> {@code setFullyConnected()} only fires when the character
 *       actually spawns ({@code receivePlayerConnect}), so a client parked on the pre-spawn screen
 *       holds a slot indefinitely while chatting happily at the packet level. A wall-clock budget
 *       kicks them; an idle rule might never.
 * </ul>
 *
 * <p>That matters because {@code GameServer.startServer} builds the peer with a hardcoded cap of
 * 101 incoming connections regardless of {@code MaxPlayers}, so on a busy server leaked slots
 * quickly exhaust the peer. Once full, RakNet answers new joiners with {@code
 * ID_NO_FREE_INCOMING_CONNECTIONS}, which the client never handles — it sits on "Getting Server
 * Info..." forever with no error and no timeout.
 *
 * <p>Exempt states get their clock re-stamped every sweep, so their 7-minute budget starts when the
 * exemption ends, not when they connected:
 *
 * <ul>
 *   <li>waiting in the login queue (queue wait is not the client's fault; the queue has its own
 *       {@code loginQueueConnectTimeout} and hands stale entries back to us),
 *   <li>awaiting co-op approval,
 *   <li>pending Google auth that has not itself timed out.
 * </ul>
 *
 * <p>Fully-connected players are never candidates. All tracking state is written only from the
 * server main thread inside {@link #sweep()}, which {@code GameServer.launchCommandHandler} exit
 * advice calls once per tick — {@code GameServer.disconnect} touches chat and connection buffers
 * whose locks invert against the network thread, so the sweep must never run anywhere else.
 */
public class StalledConnectionReaper {

    /** Wall-clock budget for completing login + spawning in, once past any exempt state. */
    public static final long DEFAULT_CONNECT_TIMEOUT_MS = 7L * 60L * 1000L;

    public static final long DEFAULT_SWEEP_INTERVAL_MS = 30000L;

    /** Matches {@code UdpEngine.connectionArray}, which is a fixed 256 entries. */
    private static final int MAX_SLOTS = 256;

    /** First time the sweep saw each slot's current occupant, guarded by {@link #SLOT_GUID}. */
    private static final long[] FIRST_SEEN_MS = new long[MAX_SLOTS];

    /** GUID owning each slot's {@link #FIRST_SEEN_MS} entry; slots are reused across clients. */
    private static final long[] SLOT_GUID = new long[MAX_SLOTS];

    private static volatile long connectTimeoutMs =
            resolvePositiveMsProperty("storm.reapStalledConnectionMs", DEFAULT_CONNECT_TIMEOUT_MS);

    private static final long SWEEP_INTERVAL_MS =
            resolvePositiveMsProperty("storm.reapSweepIntervalMs", DEFAULT_SWEEP_INTERVAL_MS);

    private static long nextSweepMs;

    private static long reapedCount;

    private StalledConnectionReaper() {}

    private static long resolvePositiveMsProperty(String key, long fallback) {
        String property = System.getProperty(key);
        if (property == null || property.isEmpty()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(property.trim());
            if (parsed > 0L) {
                return parsed;
            }
            LOGGER.warn("Storm: -D{}={} is not positive, using {}ms", key, property, fallback);
        } catch (NumberFormatException e) {
            LOGGER.warn("Storm: -D{}={} is not a number, using {}ms", key, property, fallback);
        }
        return fallback;
    }

    public static long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    /** Live-updates the connect budget. Returns the value applied. */
    public static long setConnectTimeoutMs(long millis) {
        if (millis <= 0L) {
            throw new IllegalArgumentException("connect timeout must be positive: " + millis);
        }
        connectTimeoutMs = millis;
        LOGGER.info("Storm: stalled-connection connect budget set to {}ms", millis);
        return millis;
    }

    public static long getSweepIntervalMs() {
        return SWEEP_INTERVAL_MS;
    }

    /** Number of connections dropped by {@link #sweep()} since server start. */
    public static long getReapedCount() {
        return reapedCount;
    }

    /**
     * Millis {@code connection} has spent on the reap clock, or {@code 0} when it is not on it —
     * either because no sweep has seen it yet, or because it is fully connected, or because it is
     * in an exempt state that keeps restamping the clock.
     *
     * <p>This, not wall-clock age since connect, is the number compared against {@link
     * #getConnectTimeoutMs()}. Exposed so {@code storm_connection_reap_age_seconds_max} reports the
     * reaper's own view rather than a second, subtly different clock.
     */
    public static long getReapAgeMs(UdpConnection connection, long nowMs) {
        int slot = connection.getIndex();
        if (slot < 0 || slot >= MAX_SLOTS) {
            return 0L;
        }
        if (SLOT_GUID[slot] != connection.getConnectedGUID()) {
            return 0L;
        }
        long firstSeenMs = FIRST_SEEN_MS[slot];
        return firstSeenMs == 0L ? 0L : nowMs - firstSeenMs;
    }

    /**
     * Drops connections that outstayed their connect budget. Must run on the server main thread —
     * see the class comment for the lock-inversion constraint.
     */
    public static void sweep() {
        UdpEngine engine = GameServer.udpEngine;
        if (engine == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextSweepMs) {
            return;
        }
        nextSweepMs = now + SWEEP_INTERVAL_MS;
        long timeout = connectTimeoutMs;

        for (int i = engine.connections.size() - 1; i >= 0; i--) {
            try {
                UdpConnection connection = engine.connections.get(i);
                if (connection == null) {
                    continue;
                }
                int slot = connection.getIndex();
                if (slot < 0 || slot >= MAX_SLOTS) {
                    continue;
                }
                if (connection.isFullyConnected()) {
                    SLOT_GUID[slot] = 0L;
                    FIRST_SEEN_MS[slot] = 0L;
                    continue;
                }
                long guid = connection.getConnectedGUID();
                if (SLOT_GUID[slot] != guid || FIRST_SEEN_MS[slot] == 0L) {
                    // First sweep that sees this occupant: start its clock now.
                    SLOT_GUID[slot] = guid;
                    FIRST_SEEN_MS[slot] = now;
                    continue;
                }
                if (isExempt(connection)) {
                    // Slide the clock while exempt so the budget starts when the exemption ends.
                    FIRST_SEEN_MS[slot] = now;
                    continue;
                }
                long ageMs = now - FIRST_SEEN_MS[slot];
                if (ageMs < timeout) {
                    continue;
                }
                reapedCount++;
                String stage = ConnectionStage.classify(connection);
                LOGGER.warn(
                        "Storm: reaping stalled connection {} (username={}, stage={}, {}s since"
                                + " first seen, never fully connected) — freeing RakNet slot {}",
                        connection.getIDStr(),
                        connection.getUserName(),
                        stage,
                        ageMs / 1000L,
                        slot);
                StormConnectionStageMetrics.recordReaped(stage);
                ConnectionManager.log("Storm", "stalled-connection-reap", connection);
                SLOT_GUID[slot] = 0L;
                FIRST_SEEN_MS[slot] = 0L;
                GameServer.disconnect(connection, "connection-stalled-timeout");
                engine.forceDisconnect(guid, "connection-stalled-timeout");
            } catch (Throwable t) {
                LOGGER.error("Storm: failed to reap stalled connection", t);
            }
        }
    }

    private static boolean isExempt(UdpConnection connection) {
        if (connection.awaitingCoopApprove) {
            return true;
        }
        if (LoginQueue.isInTheQueue(connection)) {
            return true;
        }
        return connection.googleAuth && !connection.isGoogleAuthTimeout();
    }
}
