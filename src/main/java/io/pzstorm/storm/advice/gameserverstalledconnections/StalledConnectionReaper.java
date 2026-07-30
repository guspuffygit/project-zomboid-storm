package io.pzstorm.storm.advice.gameserverstalledconnections;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.network.ConnectionManager;
import zombie.network.GameServer;
import zombie.network.LoginQueue;

/**
 * Reaps connections that occupy a RakNet slot but have stopped talking to the server before ever
 * reaching {@code isFullyConnected()}.
 *
 * <p>Vanilla only reaps stalled logins in {@code GameServer.main} when {@code
 * connection.getUserName() == null}. A client that sent its username and then died anywhere later in
 * the connect pipeline (workshop init, mod check, chunk download, character creation) holds its slot
 * forever — nothing else removes it. RakNet's own keepalive does not help either: the peer stays
 * responsive at the RakNet layer while the game-level handshake is dead.
 *
 * <p>That matters because {@code GameServer.main} builds the peer with a hardcoded cap of 101
 * incoming connections regardless of {@code MaxPlayers}, so on a busy server leaked slots quickly
 * exhaust the peer. Once full, RakNet answers new joiners with {@code
 * ID_NO_FREE_INCOMING_CONNECTIONS}, which the client never handles — it sits on "Getting Server
 * Info..." forever with no error and no timeout.
 *
 * <p>Activity is tracked per connection slot by {@link #recordActivity(UdpConnection)}, called for
 * every inbound game packet from the {@code UdpEngine} thread. {@link #sweep()} runs on the server
 * main thread from {@code GameServer.launchCommandHandler} and disconnects any non-fully-connected
 * connection silent for longer than {@link #getIdleTimeoutMs()}.
 */
public class StalledConnectionReaper {

    /** Idle window before a stalled connecting client is dropped. */
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 7L * 60L * 1000L;

    /** Matches {@code UdpEngine.connectionArray}, which is a fixed 256 entries. */
    public static final int MAX_SLOTS = 256;

    public static final long SWEEP_INTERVAL_MS = 30000L;

    /** Last inbound game packet per connection slot, guarded by {@link #ACTIVITY_GUID}. */
    public static final long[] LAST_ACTIVITY_MS = new long[MAX_SLOTS];

    /** GUID owning each slot's {@link #LAST_ACTIVITY_MS} entry; slots are reused across clients. */
    public static final long[] ACTIVITY_GUID = new long[MAX_SLOTS];

    public static volatile long idleTimeoutMs = resolveIdleTimeoutMs();

    public static long nextSweepMs;

    public static long reapedCount;

    private StalledConnectionReaper() {}

    private static long resolveIdleTimeoutMs() {
        String property = System.getProperty("storm.reapStalledConnectionMs");
        if (property == null || property.isEmpty()) {
            return DEFAULT_IDLE_TIMEOUT_MS;
        }
        try {
            long parsed = Long.parseLong(property.trim());
            if (parsed > 0L) {
                return parsed;
            }
            LOGGER.warn(
                    "Storm: -Dstorm.reapStalledConnectionMs={} is not positive, using {}ms",
                    property,
                    DEFAULT_IDLE_TIMEOUT_MS);
        } catch (NumberFormatException e) {
            LOGGER.warn(
                    "Storm: -Dstorm.reapStalledConnectionMs={} is not a number, using {}ms",
                    property,
                    DEFAULT_IDLE_TIMEOUT_MS);
        }
        return DEFAULT_IDLE_TIMEOUT_MS;
    }

    public static long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    /** Live-updates the idle window. Returns the value applied. */
    public static long setIdleTimeoutMs(long millis) {
        if (millis <= 0L) {
            throw new IllegalArgumentException("idle timeout must be positive: " + millis);
        }
        idleTimeoutMs = millis;
        LOGGER.info("Storm: stalled-connection reap idle window set to {}ms", millis);
        return millis;
    }

    /** Number of connections dropped by {@link #sweep()} since server start. */
    public static long getReapedCount() {
        return reapedCount;
    }

    /**
     * Stamps a connection as active. Called from the {@code UdpEngine} thread for every inbound
     * game packet, so it stays allocation-free and skips connections that already completed the
     * handshake — those are never reaped.
     */
    public static void recordActivity(UdpConnection connection) {
        if (connection == null || connection.isFullyConnected()) {
            return;
        }
        int slot = connection.getIndex();
        if (slot < 0 || slot >= MAX_SLOTS) {
            return;
        }
        ACTIVITY_GUID[slot] = connection.getConnectedGUID();
        LAST_ACTIVITY_MS[slot] = System.currentTimeMillis();
    }

    /**
     * Drops connections stuck mid-handshake. Must run on the server main thread — {@code
     * GameServer.disconnect} touches chat and connection buffers whose locks invert against the
     * network thread.
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
        long timeout = idleTimeoutMs;

        for (int i = engine.connections.size() - 1; i >= 0; i--) {
            UdpConnection connection = engine.connections.get(i);
            if (connection == null) {
                continue;
            }
            try {
                if (!isReapable(connection)) {
                    continue;
                }
                int slot = connection.getIndex();
                if (slot < 0 || slot >= MAX_SLOTS) {
                    continue;
                }
                long guid = connection.getConnectedGUID();
                if (ACTIVITY_GUID[slot] != guid || LAST_ACTIVITY_MS[slot] == 0L) {
                    // First time this sweep has seen the connection: give it a full window
                    // rather than judging it on a timestamp we never recorded.
                    ACTIVITY_GUID[slot] = guid;
                    LAST_ACTIVITY_MS[slot] = now;
                    continue;
                }
                long idleMs = now - LAST_ACTIVITY_MS[slot];
                if (idleMs < timeout) {
                    continue;
                }
                reapedCount++;
                LOGGER.warn(
                        "Storm: reaping stalled connection {} (username={}, idle {}s, not fully"
                                + " connected) — freeing RakNet slot {}",
                        connection.getIDStr(),
                        connection.getUserName(),
                        idleMs / 1000L,
                        slot);
                ConnectionManager.log("Storm", "stalled-connection-reap", connection);
                LAST_ACTIVITY_MS[slot] = 0L;
                ACTIVITY_GUID[slot] = 0L;
                GameServer.disconnect(connection, "connection-idle-timeout");
                engine.forceDisconnect(guid, "connection-idle-timeout");
            } catch (Throwable t) {
                LOGGER.error("Storm: failed to reap stalled connection", t);
            }
        }
    }

    private static boolean isReapable(UdpConnection connection) {
        if (connection.isFullyConnected()) {
            return false;
        }
        if (connection.awaitingCoopApprove) {
            return false;
        }
        if (LoginQueue.isInTheQueue(connection)) {
            return false;
        }
        return !connection.googleAuth || connection.isGoogleAuthTimeout();
    }
}
