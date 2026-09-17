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
import zombie.core.znet.SteamUtils;
import zombie.network.GameServer;

/**
 * Server-side registry of "Storm connections": RakNet connections whose client also opened the
 * game-port TCP channel and completed the {@code POST /storm/handshake}. A marked connection can
 * receive connection-phase data over TCP instead of UDP.
 *
 * <p>Binding model: the handshake claims a steamId, and the claim is accepted only if a live {@link
 * UdpConnection} exists whose UDP source IP equals the TCP socket's source IP and whose steamId
 * matches. After the LoginPacket that steamId is the one vanilla stamped on the connection ({@link
 * #handshake}); before it, only connections that have not sent a LoginPacket qualify, the steamId
 * is checked against RakNet's own view of the peer ({@code UdpEngine.getClientSteamID}, the same
 * source vanilla stamps from) and the match must be unique for that IP ({@link
 * #handshakePreLogin}). That defeats off-path hijack (an attacker cannot bind a session to a
 * connection they don't share an IP with). A same-NAT attacker who also knows the victim's steamId
 * can bind the victim's pre-login connection and post a LoginPacket on it, which logs the victim's
 * RakNet connection in under the attacker's own credentials and gains the attacker nothing beyond
 * what a same-NAT UDP injection already could. Since {@code POST /storm/game/login} carries the
 * vanilla LoginPacket (username + password, cleartext exactly as vanilla sends it over UDP), the
 * TCP channel does not widen the credential exposure either.
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
        return bind(match, steamId, clientStormVersion, tcpSourceIp);
    }

    private static Session bind(
            UdpConnection match, long steamId, String clientStormVersion, String tcpSourceIp) {
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
     * Bind a handshake to a connection that has not yet sent its LoginPacket. Server main thread
     * only: it reads the peer address and steamId through RakNet natives. Returns {@code null}
     * unless exactly one pre-login connection from {@code tcpSourceIp} matches; in Steam mode the
     * claimed steamId must also equal RakNet's steamId for that peer, in nosteam mode the session's
     * steamId is recorded as 0 (vanilla never stamps one). An earlier binding on the same GUID does
     * not exclude a connection: RakNet keeps the GUID across a reconnect, so the stale session must
     * be replaced rather than honored.
     *
     * <p>Vanilla reaps a connection that has not logged in within 5 s of connecting ({@code
     * UdpConnection.isConnectionAttemptTimeout}); a successful binding re-stamps that clock so the
     * client's immediate login post is not raced by the reap. The extension is bounded to one more
     * 5 s window, so a client that handshakes and never logs in is still dropped.
     */
    public static @Nullable Session handshakePreLogin(
            long claimedSteamId, String clientStormVersion, String tcpSourceIp) {
        UdpEngine engine = GameServer.udpEngine;
        if (engine == null) {
            return null;
        }
        boolean steam = SteamUtils.isSteamModeEnabled();
        UdpConnection match = null;
        for (int i = 0; i < engine.connections.size(); i++) {
            UdpConnection connection = engine.connections.get(i);
            if (connection == null || connection.getUserName() != null) {
                continue;
            }
            long guid = connection.getConnectedGUID();
            java.net.InetSocketAddress address = connection.getInetSocketAddress();
            if (address == null || !tcpSourceIp.equals(address.getHostString())) {
                continue;
            }
            if (steam && engine.getClientSteamID(guid) != claimedSteamId) {
                continue;
            }
            if (match != null) {
                LOGGER.info(
                        "Pre-login Storm handshake from {} matches more than one connection;"
                                + " refusing",
                        tcpSourceIp);
                return null;
            }
            match = connection;
        }
        if (match == null) {
            return null;
        }
        match.connectionTimestamp = System.currentTimeMillis();
        return bind(match, steam ? claimedSteamId : 0L, clientStormVersion, tcpSourceIp);
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
        if (connection == null) {
            return null;
        }
        // 0 is the pre-login value; vanilla stamps the real steamId in LoginPacket.processServer.
        long steamId = connection.getSteamId();
        if (steamId != 0L && steamId != session.steamId()) {
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
        UdpConnection match = null;
        for (int i = 0; i < connections.size(); i++) {
            UdpConnection connection;
            try {
                connection = connections.get(i);
            } catch (IndexOutOfBoundsException e) {
                break;
            }
            if (connection == null
                    || connection.getUserName() == null
                    || connection.getSteamId() != steamId
                    || !ip.equals(connection.getIP())) {
                continue;
            }
            if (match != null) {
                // Nosteam: steamId is 0 on every connection, so two logged-in players behind one
                // NAT are indistinguishable here. Prefer the one nobody has bound yet.
                Session bound = BY_GUID.get(match.getConnectedGUID());
                boolean matchBound = bound != null && liveConnection(bound) != null;
                Session candidateBound = BY_GUID.get(connection.getConnectedGUID());
                boolean thisBound =
                        candidateBound != null && liveConnection(candidateBound) != null;
                if (matchBound == thisBound) {
                    LOGGER.info(
                            "Storm handshake from {} matches more than one logged-in connection;"
                                    + " refusing",
                            ip);
                    return null;
                }
                if (matchBound) {
                    match = connection;
                }
                continue;
            }
            match = connection;
        }
        return match;
    }
}
