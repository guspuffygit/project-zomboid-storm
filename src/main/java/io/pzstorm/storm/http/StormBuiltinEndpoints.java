package io.pzstorm.storm.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.pzstorm.storm.core.StormVersion;
import io.pzstorm.storm.patch.networking.GameServerTickRatePatch.UpdateLimitFactory;
import io.pzstorm.storm.patch.networking.ServerFpsConfig;
import io.pzstorm.storm.patch.networking.ServerLockFpsConfig;
import io.pzstorm.storm.patch.performance.AnimalLOSTickInterval;
import io.pzstorm.storm.patch.performance.IsoPhysicsObjectFpsConfig;
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

    public record ServerLockFpsDto(int lockFps) {}

    public record ServerLockFpsUpdateDto(int requested, int applied) {}

    public record IsoPhysicsServerFpsDto(int fps) {}

    public record IsoPhysicsServerFpsUpdateDto(int requested, int applied) {}

    public record ServerFpsDto(
            long tickIntervalMs,
            @JsonSerialize(using = TwoDecimalDoubleSerializer.class) Double tps,
            int lockFps,
            int physicsFps) {}

    public record ServerFpsUpdateDto(
            int requestedFps,
            int appliedFps,
            long tickIntervalMs,
            @JsonSerialize(using = TwoDecimalDoubleSerializer.class) Double tps,
            int lockFps,
            int physicsFps) {}

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

    @HttpEndpoint(path = "/storm/server/lockFps")
    public static void getServerLockFps(HttpRequestEvent event) throws IOException {
        int fps = ServerLockFpsConfig.getCurrentLockFps();
        event.sendJson(200, MAPPER.writeValueAsString(new ServerLockFpsDto(fps)));
    }

    @HttpEndpoint(path = "/storm/server/lockFps", method = "POST")
    public static void setServerLockFps(HttpRequestEvent event) throws IOException {
        String fpsParam = event.getQueryParams().get("fps");
        if (fpsParam == null || fpsParam.isEmpty()) {
            event.send(400, "missing required query parameter: fps");
            return;
        }
        int requested;
        try {
            requested = Integer.parseInt(fpsParam.trim());
        } catch (NumberFormatException e) {
            event.send(400, "fps must be an integer, got: " + fpsParam);
            return;
        }
        int applied = ServerLockFpsConfig.setLockFps(requested);
        event.sendJson(
                200, MAPPER.writeValueAsString(new ServerLockFpsUpdateDto(requested, applied)));
    }

    @HttpEndpoint(path = "/storm/isoPhysics/serverFps")
    public static void getIsoPhysicsServerFps(HttpRequestEvent event) throws IOException {
        int fps = IsoPhysicsObjectFpsConfig.getCurrentPhysicsFps();
        event.sendJson(200, MAPPER.writeValueAsString(new IsoPhysicsServerFpsDto(fps)));
    }

    @HttpEndpoint(path = "/storm/isoPhysics/serverFps", method = "POST")
    public static void setIsoPhysicsServerFps(HttpRequestEvent event) throws IOException {
        String fpsParam = event.getQueryParams().get("fps");
        if (fpsParam == null || fpsParam.isEmpty()) {
            event.send(400, "missing required query parameter: fps");
            return;
        }
        int requested;
        try {
            requested = Integer.parseInt(fpsParam.trim());
        } catch (NumberFormatException e) {
            event.send(400, "fps must be an integer, got: " + fpsParam);
            return;
        }
        int applied = IsoPhysicsObjectFpsConfig.setPhysicsFps(requested);
        event.sendJson(
                200,
                MAPPER.writeValueAsString(new IsoPhysicsServerFpsUpdateDto(requested, applied)));
    }

    @HttpEndpoint(path = "/storm/server/fps")
    public static void getServerFps(HttpRequestEvent event) throws IOException {
        long intervalMs = UpdateLimitFactory.getCurrentTickIntervalMs();
        event.sendJson(
                200,
                MAPPER.writeValueAsString(
                        new ServerFpsDto(
                                intervalMs,
                                tps(intervalMs),
                                ServerLockFpsConfig.getCurrentLockFps(),
                                IsoPhysicsObjectFpsConfig.getCurrentPhysicsFps())));
    }

    @HttpEndpoint(path = "/storm/server/fps", method = "POST")
    public static void setServerFps(HttpRequestEvent event) throws IOException {
        String fpsParam = event.getQueryParams().get("fps");
        if (fpsParam == null || fpsParam.isEmpty()) {
            event.send(400, "missing required query parameter: fps");
            return;
        }
        int requested;
        try {
            requested = Integer.parseInt(fpsParam.trim());
        } catch (NumberFormatException e) {
            event.send(400, "fps must be an integer, got: " + fpsParam);
            return;
        }
        ServerFpsConfig.AppliedFps applied;
        try {
            applied = ServerFpsConfig.applyUnifiedFps(requested);
        } catch (IllegalStateException e) {
            event.send(503, e.getMessage());
            return;
        }
        event.sendJson(
                200,
                MAPPER.writeValueAsString(
                        new ServerFpsUpdateDto(
                                applied.requestedFps(),
                                applied.appliedFps(),
                                applied.appliedTickIntervalMs(),
                                tps(applied.appliedTickIntervalMs()),
                                applied.appliedLockFps(),
                                applied.appliedPhysicsFps())));
    }

    private static Double tps(long intervalMs) {
        return intervalMs <= 0 ? null : 1000.0 / intervalMs;
    }
}
