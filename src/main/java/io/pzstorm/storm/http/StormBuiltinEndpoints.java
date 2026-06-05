package io.pzstorm.storm.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.pzstorm.storm.core.StormVersion;
import io.pzstorm.storm.los.StormServerLosConfig;
import io.pzstorm.storm.patch.networking.GameServerTickRatePatch.UpdateLimitFactory;
import io.pzstorm.storm.patch.networking.ServerFpsConfig;
import io.pzstorm.storm.patch.networking.ServerLockFpsConfig;
import io.pzstorm.storm.patch.performance.AnimalLOSTickInterval;
import io.pzstorm.storm.patch.performance.IsoPhysicsObjectFpsConfig;
import io.pzstorm.storm.patch.performance.StormZombieCullConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.znet.SteamUtils;
import zombie.network.GameServer;

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

    public record ServerLosThreadsDto(int threads) {}

    public record ServerLosThreadsUpdateDto(int requested, int applied) {}

    public record ZombieCullDisabledDto(boolean disabled) {}

    public record ZombieCullDisabledUpdateDto(boolean requested, boolean applied) {}

    public record ZombieCullThresholdDto(int threshold) {}

    public record ZombieCullThresholdUpdateDto(int requested, int applied) {}

    public record ConnectedPlayerDto(String username, String steamId, String ip) {}

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

    @HttpEndpoint(path = "/storm/serverLos/threads")
    public static void getServerLosThreads(HttpRequestEvent event) throws IOException {
        event.sendJson(
                200,
                MAPPER.writeValueAsString(new ServerLosThreadsDto(StormServerLosConfig.threads())));
    }

    @HttpEndpoint(path = "/storm/serverLos/threads", method = "POST")
    public static void setServerLosThreads(HttpRequestEvent event) throws IOException {
        String nParam = event.getQueryParams().get("n");
        if (nParam == null || nParam.isEmpty()) {
            event.send(400, "missing required query parameter: n");
            return;
        }
        int requested;
        try {
            requested = Integer.parseInt(nParam.trim());
        } catch (NumberFormatException e) {
            event.send(400, "n must be an integer, got: " + nParam);
            return;
        }
        int applied = StormServerLosConfig.setThreads(requested);
        event.sendJson(
                200, MAPPER.writeValueAsString(new ServerLosThreadsUpdateDto(requested, applied)));
    }

    @HttpEndpoint(path = "/storm/server/zombieCull/disabled")
    public static void getZombieCullDisabled(HttpRequestEvent event) throws IOException {
        event.sendJson(
                200,
                MAPPER.writeValueAsString(
                        new ZombieCullDisabledDto(StormZombieCullConfig.isDisabled())));
    }

    @HttpEndpoint(path = "/storm/server/zombieCull/disabled", method = "POST")
    public static void setZombieCullDisabled(HttpRequestEvent event) throws IOException {
        String param = event.getQueryParams().get("disabled");
        if (param == null || param.isEmpty()) {
            event.send(400, "missing required query parameter: disabled");
            return;
        }
        String normalised = param.trim().toLowerCase();
        boolean requested;
        if ("true".equals(normalised)) {
            requested = true;
        } else if ("false".equals(normalised)) {
            requested = false;
        } else {
            event.send(400, "disabled must be true or false, got: " + param);
            return;
        }
        boolean applied = StormZombieCullConfig.setDisabled(requested);
        event.sendJson(
                200,
                MAPPER.writeValueAsString(new ZombieCullDisabledUpdateDto(requested, applied)));
    }

    @HttpEndpoint(path = "/storm/server/zombieCull/threshold")
    public static void getZombieCullThreshold(HttpRequestEvent event) throws IOException {
        event.sendJson(
                200,
                MAPPER.writeValueAsString(
                        new ZombieCullThresholdDto(StormZombieCullConfig.getThreshold())));
    }

    @HttpEndpoint(path = "/storm/server/zombieCull/threshold", method = "POST")
    public static void setZombieCullThreshold(HttpRequestEvent event) throws IOException {
        String nParam = event.getQueryParams().get("n");
        if (nParam == null || nParam.isEmpty()) {
            event.send(400, "missing required query parameter: n");
            return;
        }
        int requested;
        try {
            requested = Integer.parseInt(nParam.trim());
        } catch (NumberFormatException e) {
            event.send(400, "n must be an integer, got: " + nParam);
            return;
        }
        int applied = StormZombieCullConfig.setThreshold(requested);
        event.sendJson(
                200,
                MAPPER.writeValueAsString(new ZombieCullThresholdUpdateDto(requested, applied)));
    }

    @HttpEndpoint(path = "/storm/server/players")
    public static void getConnectedPlayers(HttpRequestEvent event) throws IOException {
        List<ConnectedPlayerDto> players = new ArrayList<>();
        UdpEngine engine = GameServer.udpEngine;
        if (engine != null) {
            List<UdpConnection> connections = engine.connections;
            // Connections are mutated on the server main thread while this handler runs on
            // the HTTP-Dispatcher thread, so iterate by index with a bounds guard rather
            // than with an iterator (which could throw ConcurrentModificationException).
            // Vanilla PZ iterates this same list by index for the same reason.
            for (int i = 0; i < connections.size(); i++) {
                UdpConnection connection;
                try {
                    connection = connections.get(i);
                } catch (IndexOutOfBoundsException e) {
                    break;
                }
                if (connection == null || !connection.isFullyConnected()) {
                    continue;
                }
                players.add(
                        new ConnectedPlayerDto(
                                connection.getUserName(),
                                SteamUtils.convertSteamIDToString(connection.getSteamId()),
                                connection.getIP()));
            }
        }
        event.sendJson(200, MAPPER.writeValueAsString(players));
    }

    private static Double tps(long intervalMs) {
        return intervalMs <= 0 ? null : 1000.0 / intervalMs;
    }
}
