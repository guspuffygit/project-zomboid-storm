package io.pzstorm.storm.connection;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.network.GameServer;

/**
 * Server-side registry of "Storm connections": RakNet connections whose client also opened the
 * game-port TCP channel and completed the {@code POST /storm/handshake}. A marked connection can
 * receive connection-phase data over TCP instead of UDP.
 *
 * <p>Binding model (v1): the handshake claims a steamId, and the claim is accepted only if a live
 * {@link UdpConnection} exists with that steamId whose UDP source IP equals the TCP socket's source
 * IP. That defeats off-path hijack (an attacker cannot bind a session to a connection they don't
 * share an IP with); an on-path/same-NAT attacker gains only the connection-phase data any
 * legitimately connecting client is served. If credential-bearing traffic ever moves onto the TCP
 * channel, upgrade the binding to a server-issued challenge echoed over the UDP connection first.
 *
 * <p>Sessions are validated lazily: a token is only honored while its RakNet connection is still
 * alive ({@link UdpEngine#getActiveConnection(long)}), so a dropped player invalidates the TCP
 * session with no disconnect hook required.
 *
 * <p>Thread safety: all state lives in concurrent maps and lookups only touch {@code UdpConnection}
 * identity fields (guid, steamId, ip), so calls are safe from the game-port HTTP pool threads.
 * Never reach from here into chat or packet-send paths off the server main thread.
 */
public final class StormTcpSessionRegistry {

    /** Header carrying the session token on authenticated game-port requests. */
    public static final String SESSION_HEADER = "X-Storm-Session";

    public record Session(
            String token, long guid, long steamId, String clientStormVersion, String ip) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Map<String, Session> BY_TOKEN = new ConcurrentHashMap<>();
    private static final Map<Long, Session> BY_GUID = new ConcurrentHashMap<>();

    private StormTcpSessionRegistry() {}

    /**
     * Attempt to bind a TCP handshake to a live RakNet connection. Returns the created session, or
     * {@code null} if no live connection matches the claimed steamId + source IP.
     */
    public static @Nullable Session handshake(
            long steamId, String clientStormVersion, String tcpSourceIp) {
        UdpConnection match = findConnection(steamId, tcpSourceIp);
        if (match == null) {
            return null;
        }
        long guid = match.getConnectedGUID();

        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        Session session =
                new Session(
                        HexFormat.of().formatHex(raw),
                        guid,
                        steamId,
                        clientStormVersion,
                        tcpSourceIp);

        Session previous = BY_GUID.put(guid, session);
        if (previous != null) {
            // Re-handshake for the same connection (client retry) replaces the old token.
            BY_TOKEN.remove(previous.token());
        }
        BY_TOKEN.put(session.token(), session);
        LOGGER.info(
                "Marked Storm connection: guid={} steamId={} ip={} clientStorm={}",
                guid,
                steamId,
                tcpSourceIp,
                clientStormVersion);
        return session;
    }

    /**
     * Resolve a session token, honoring it only while its RakNet connection is still alive. Expired
     * sessions are pruned on the way out.
     */
    public static @Nullable Session byToken(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Session session = BY_TOKEN.get(token);
        if (session == null) {
            return null;
        }
        if (liveConnection(session) == null) {
            drop(session);
            return null;
        }
        return session;
    }

    /** True if this connection's client completed the game-port TCP handshake. */
    public static boolean isStormConnection(UdpConnection connection) {
        Session session = BY_GUID.get(connection.getConnectedGUID());
        return session != null && liveConnection(session) != null;
    }

    /** Storm version the client on this connection sent in its handshake, or {@code null}. */
    public static @Nullable String clientStormVersion(UdpConnection connection) {
        Session session = BY_GUID.get(connection.getConnectedGUID());
        return session != null && liveConnection(session) != null
                ? session.clientStormVersion()
                : null;
    }

    /** The live {@link UdpConnection} behind a session, or {@code null} if it has dropped. */
    public static @Nullable UdpConnection liveConnection(Session session) {
        UdpEngine engine = GameServer.udpEngine;
        if (engine == null) {
            return null;
        }
        UdpConnection connection = engine.getActiveConnection(session.guid());
        if (connection == null || connection.getSteamId() != session.steamId()) {
            return null;
        }
        return connection;
    }

    /** Drop dead sessions so tokens for long-gone connections don't accumulate. */
    public static void sweep() {
        for (Iterator<Session> it = BY_TOKEN.values().iterator(); it.hasNext(); ) {
            Session session = it.next();
            if (liveConnection(session) == null) {
                it.remove();
                BY_GUID.remove(session.guid(), session);
            }
        }
    }

    public static void reset() {
        BY_TOKEN.clear();
        BY_GUID.clear();
    }

    private static void drop(Session session) {
        BY_TOKEN.remove(session.token(), session);
        BY_GUID.remove(session.guid(), session);
    }

    private static @Nullable UdpConnection findConnection(long steamId, String ip) {
        UdpEngine engine = GameServer.udpEngine;
        if (engine == null) {
            return null;
        }
        // Index-based iteration: the list is mutated on the server main thread while this runs
        // on an HTTP pool thread (same pattern as StormBuiltinEndpoints).
        java.util.List<UdpConnection> connections = engine.connections;
        for (int i = 0; i < connections.size(); i++) {
            UdpConnection connection;
            try {
                connection = connections.get(i);
            } catch (IndexOutOfBoundsException e) {
                break;
            }
            if (connection == null) {
                continue;
            }
            if (connection.getSteamId() == steamId && ip.equals(connection.getIP())) {
                return connection;
            }
        }
        return null;
    }
}
