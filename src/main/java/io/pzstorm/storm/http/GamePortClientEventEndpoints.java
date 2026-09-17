package io.pzstorm.storm.http;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pzstorm.storm.connection.StormTcpSessionRegistry;
import io.pzstorm.storm.connection.StormTcpSessionRegistry.Session;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import zombie.core.raknet.UdpConnection;

/**
 * Client-side connection diagnostics reported to the server log, so an operator reading the reap
 * line for a join that died can see what the client saw (a packet its loading drain threw away, a
 * TCP login that fell back to UDP). Text-only, bounded, rate-limited per connection, and never
 * echoed back to any client.
 */
public class GamePortClientEventEndpoints {

    static final int MAX_BODY_BYTES = 2 * 1024;
    static final int MAX_EVENT_CHARS = 64;
    static final int MAX_DETAIL_CHARS = 512;
    static final long MIN_INTERVAL_MILLIS = 5_000;
    static final int MAX_TRACKED_CONNECTIONS = 512;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<Long, Long> LAST_REPORT_MILLIS = new ConcurrentHashMap<>();

    @GameHttpEndpoint(path = "/storm/game/client-event", method = "POST")
    public static void report(HttpRequestEvent event) throws IOException {
        Session session = GamePortHandshakeEndpoints.requireSession(event);
        if (session == null) {
            return;
        }
        UdpConnection connection = StormTcpSessionRegistry.liveConnection(session);
        if (connection == null) {
            event.send(403, "game connection gone");
            return;
        }
        byte[] body = event.getRequestBody(MAX_BODY_BYTES);
        if (body == null) {
            event.send(413, "event too large");
            return;
        }
        String name;
        String detail;
        try {
            JsonNode json = MAPPER.readTree(body);
            name = sanitize(json.path("event").asText(""), MAX_EVENT_CHARS);
            detail = sanitize(json.path("detail").asText(""), MAX_DETAIL_CHARS);
        } catch (IOException e) {
            event.send(400, "body must be JSON");
            return;
        }
        if (name.isEmpty()) {
            event.send(400, "event is required");
            return;
        }
        if (!allow(connection.getConnectedGUID(), System.currentTimeMillis())) {
            event.send(429, "too many events");
            return;
        }
        LOGGER.warn(
                "Storm client event from {} (user={}): {} {}",
                connection.getIDStr(),
                connection.getUserName(),
                name,
                detail);
        event.sendEmpty(204);
    }

    /** Keeps printable characters only; log lines must stay one line and free of terminal codes. */
    static String sanitize(String text, int maxChars) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), maxChars));
        for (int i = 0; i < text.length() && out.length() < maxChars; i++) {
            char c = text.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                out.append(' ');
            } else if (c >= ' ' && c != 0x7f) {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    static boolean allow(long guid, long nowMillis) {
        if (LAST_REPORT_MILLIS.size() >= MAX_TRACKED_CONNECTIONS) {
            LAST_REPORT_MILLIS
                    .entrySet()
                    .removeIf(entry -> nowMillis - entry.getValue() >= MIN_INTERVAL_MILLIS);
            if (LAST_REPORT_MILLIS.size() >= MAX_TRACKED_CONNECTIONS) {
                return false;
            }
        }
        Long last = LAST_REPORT_MILLIS.get(guid);
        if (last != null && nowMillis - last < MIN_INTERVAL_MILLIS) {
            return false;
        }
        LAST_REPORT_MILLIS.put(guid, nowMillis);
        return true;
    }

    static void reset() {
        LAST_REPORT_MILLIS.clear();
    }
}
