package io.pzstorm.storm.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.pzstorm.storm.core.StormVersion;
import io.pzstorm.storm.patch.networking.GameServerTickRatePatch.UpdateLimitFactory;
import io.pzstorm.storm.patch.performance.AnimalLOSTickInterval;
import java.io.IOException;

/** Endpoints always registered by Storm when the HTTP server is enabled. */
public class StormBuiltinEndpoints {

    public record TickIntervalDto(
            long tickIntervalMs,
            @JsonSerialize(using = TwoDecimalDoubleSerializer.class) Double tps) {}

    public record TickIntervalUpdateDto(
            long requestedMs,
            long appliedMs,
            @JsonSerialize(using = TwoDecimalDoubleSerializer.class) Double tps) {}

    public record AnimalLOSTickIntervalDto(int tickInterval) {}

    public record AnimalLOSTickIntervalUpdateDto(int requested, int applied) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        event.sendJson(200, MAPPER.writeValueAsString(new TickIntervalDto(ms, tps(ms))));
    }

    @HttpEndpoint(path = "/storm/server/tickInterval", method = "POST")
    public static void setTickInterval(HttpRequestEvent event) throws IOException {
        String msParam = event.getQueryParams().get("ms");
        if (msParam == null || msParam.isEmpty()) {
            event.send(400, "missing required query parameter: ms");
            return;
        }
        long requested;
        try {
            requested = Long.parseLong(msParam.trim());
        } catch (NumberFormatException e) {
            event.send(400, "ms must be an integer, got: " + msParam);
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
                MAPPER.writeValueAsString(
                        new TickIntervalUpdateDto(requested, applied, tps(applied))));
    }

    @HttpEndpoint(path = "/storm/animalLOS/tickInterval")
    public static void getAnimalLOSTickInterval(HttpRequestEvent event) throws IOException {
        int ticks = AnimalLOSTickInterval.getCurrentTickInterval();
        event.sendJson(200, MAPPER.writeValueAsString(new AnimalLOSTickIntervalDto(ticks)));
    }

    @HttpEndpoint(path = "/storm/animalLOS/tickInterval", method = "POST")
    public static void setAnimalLOSTickInterval(HttpRequestEvent event) throws IOException {
        String ticksParam = event.getQueryParams().get("ticks");
        if (ticksParam == null || ticksParam.isEmpty()) {
            event.send(400, "missing required query parameter: ticks");
            return;
        }
        int requested;
        try {
            requested = Integer.parseInt(ticksParam.trim());
        } catch (NumberFormatException e) {
            event.send(400, "ticks must be an integer, got: " + ticksParam);
            return;
        }
        int applied = AnimalLOSTickInterval.setTickInterval(requested);
        event.sendJson(
                200,
                MAPPER.writeValueAsString(new AnimalLOSTickIntervalUpdateDto(requested, applied)));
    }

    private static Double tps(long intervalMs) {
        return intervalMs <= 0 ? null : 1000.0 / intervalMs;
    }
}
