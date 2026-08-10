package io.pzstorm.storm.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pzstorm.storm.connection.StormTcpSessionRegistry;
import io.pzstorm.storm.connection.StormTcpSessionRegistry.Session;
import io.pzstorm.storm.core.StormVersion;
import java.io.IOException;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Game-port handshake that marks a RakNet connection as a Storm connection. A launcher-launched
 * client dials TCP on the game port after its UDP connection is up and posts its identity; on
 * success it gets a session token that authenticates all subsequent game-port requests (sent in the
 * {@link StormTcpSessionRegistry#SESSION_HEADER} header).
 */
public class GamePortHandshakeEndpoints {

    public record HandshakeRequest(
            @JsonProperty(required = true) String steamId, String stormVersion) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
