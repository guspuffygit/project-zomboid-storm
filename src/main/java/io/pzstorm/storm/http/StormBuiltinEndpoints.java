package io.pzstorm.storm.http;

import io.pzstorm.storm.core.StormVersion;
import io.pzstorm.storm.patch.networking.GameServerTickRatePatch;
import io.pzstorm.storm.patch.networking.GameServerTickRatePatch.UpdateLimitFactory;
import java.io.IOException;

/** Endpoints always registered by Storm when the HTTP server is enabled. */
public class StormBuiltinEndpoints {

    @HttpEndpoint(path = "/health")
    public static void health(HttpRequestEvent event) throws IOException {
        event.send(200, "OK");
    }

    @HttpEndpoint(path = "/storm/version")
    public static void version(HttpRequestEvent event) throws IOException {
        event.send(200, StormVersion.getVersion());
    }

    @HttpEndpoint(path = "/storm/server/tickInterval")
    public static void getTickInterval(HttpRequestEvent event) throws IOException {
        long ms = UpdateLimitFactory.getCurrentTickIntervalMs();
        event.sendJson(
                200,
                "{\"tickIntervalMs\":"
                        + ms
                        + ",\"tps\":"
                        + formatTpsJson(ms)
                        + ",\"auto\":"
                        + UpdateLimitFactory.isAutoModeActive()
                        + "}");
    }

    @HttpEndpoint(path = "/storm/server/tickInterval", method = "POST")
    public static void setTickInterval(HttpRequestEvent event) throws IOException {
        String msParam = event.getQueryParams().get("ms");
        if (msParam == null || msParam.isEmpty()) {
            event.send(400, "missing required query parameter: ms");
            return;
        }
        String trimmed = msParam.trim();
        if (GameServerTickRatePatch.AUTO_PROPERTY_VALUE.equalsIgnoreCase(trimmed)) {
            long applied;
            try {
                applied = UpdateLimitFactory.enableAutoMode();
            } catch (IllegalStateException e) {
                event.send(503, e.getMessage());
                return;
            }
            event.sendJson(
                    200,
                    "{\"requestedMs\":\"auto\",\"appliedMs\":"
                            + applied
                            + ",\"tps\":"
                            + formatTpsJson(applied)
                            + ",\"auto\":true}");
            return;
        }
        long requested;
        try {
            requested = Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            event.send(400, "ms must be an integer or 'auto', got: " + msParam);
            return;
        }
        long applied;
        try {
            applied = UpdateLimitFactory.setTickIntervalMs(requested);
        } catch (IllegalStateException e) {
            event.send(503, e.getMessage());
            return;
        }
        event.sendJson(
                200,
                "{\"requestedMs\":"
                        + requested
                        + ",\"appliedMs\":"
                        + applied
                        + ",\"tps\":"
                        + formatTpsJson(applied)
                        + ",\"auto\":false}");
    }

    private static String formatTpsJson(long intervalMs) {
        return intervalMs <= 0 ? "null" : String.format("%.2f", 1000.0 / intervalMs);
    }
}
