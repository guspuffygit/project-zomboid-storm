package io.pzstorm.storm.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pzstorm.storm.connection.StormTcpSessionRegistry;
import io.pzstorm.storm.connection.StormTcpSessionRegistry.Session;
import io.pzstorm.storm.core.StormVersion;
import io.pzstorm.storm.util.StormServerTaskQueue;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jetbrains.annotations.Nullable;
import zombie.characters.Capability;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

/**
 * Game-port handshake that marks a RakNet connection as a Storm connection. A launcher-launched
 * client dials TCP on the game port as soon as its UDP connection is up and posts its identity; on
 * success it gets a session token that authenticates all subsequent game-port requests (sent in the
 * {@link StormTcpSessionRegistry#SESSION_HEADER} header).
 *
 * <p>Two bindings are tried in order: the post-login match on the steamId vanilla stamped (cheap,
 * off-thread) and then the pre-login match against RakNet's own peer data, which needs the server
 * main thread. A Storm client holds its LoginPacket for the pre-login binding so it can post the
 * login over TCP with retries.
 */
public class GamePortHandshakeEndpoints {

    public record HandshakeRequest(
            @JsonProperty(required = true) String steamId, String stormVersion) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TASK_TIMEOUT_SECONDS = 15;

    @GameHttpEndpoint(path = "/storm/handshake", method = "POST")
    public static void handshake(HttpRequestEvent event, HandshakeRequest body) throws IOException {
        long steamId;
        try {
            steamId = Long.parseLong(body.steamId().trim());
        } catch (NumberFormatException e) {
            event.send(400, "steamId must be a decimal steam id");
            return;
        }

        String sourceIp = event.getRemoteAddress().getAddress().getHostAddress();
        String clientStorm = body.stormVersion() == null ? "unknown" : body.stormVersion();
        Session session = StormTcpSessionRegistry.handshake(steamId, clientStorm, sourceIp);
        if (session == null && GameServer.udpEngine != null) {
            try {
                session =
                        StormServerTaskQueue.submit(
                                        () ->
                                                StormTcpSessionRegistry.handshakePreLogin(
                                                        steamId, clientStorm, sourceIp))
                                .get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                event.send(503, "server busy");
                return;
            } catch (Exception e) {
                event.send(500, "handshake failed");
                return;
            }
        }
        if (session == null) {
            // No live RakNet connection matches the claim; the client should fall back to UDP.
            event.send(403, "no matching game connection");
            return;
        }
        event.sendJson(
                200,
                MAPPER.writeValueAsString(
                        Map.of(
                                "sessionToken", session.token(),
                                "serverStormVersion", StormVersion.getVersion())));
    }

    /**
     * Resolve the session on an authenticated game-port request, or send a 401 and return {@code
     * null}. Endpoint handlers for Storm-connection data should start with this.
     */
    public static @Nullable Session requireSession(HttpRequestEvent event) throws IOException {
        Session session = StormTcpSessionRegistry.byToken(sessionHeader(event));
        if (session == null) {
            event.send(401, "missing or expired " + StormTcpSessionRegistry.SESSION_HEADER);
            return null;
        }
        return session;
    }

    /**
     * Resolve the live game connection behind a session, or send a 403 and return {@code null}.
     * Mirrors the vanilla {@code @PacketSetting(requiredCapability)} gate for endpoints that stand
     * in for a login-phase packet.
     */
    public static @Nullable UdpConnection requireConnection(
            HttpRequestEvent event, Session session, Capability capability) throws IOException {
        UdpConnection connection = StormTcpSessionRegistry.liveConnection(session);
        if (connection == null) {
            event.send(403, "game connection gone");
            return null;
        }
        if (connection.getRole() == null || !connection.getRole().hasCapability(capability)) {
            event.send(403, "missing capability " + capability);
            return null;
        }
        return connection;
    }

    /** Case-insensitive header lookup: the JDK server normalizes header-name casing. */
    private static @Nullable String sessionHeader(HttpRequestEvent event) {
        for (Map.Entry<String, String> header : event.getRequestHeaders().entrySet()) {
            if (StormTcpSessionRegistry.SESSION_HEADER.equalsIgnoreCase(header.getKey())) {
                return header.getValue();
            }
        }
        return null;
    }
}
