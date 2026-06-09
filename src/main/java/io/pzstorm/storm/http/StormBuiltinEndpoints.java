package io.pzstorm.storm.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pzstorm.storm.core.StormVersion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.znet.SteamUtils;
import zombie.network.GameServer;

/** Endpoints always registered by Storm when the HTTP server is enabled. */
public class StormBuiltinEndpoints {

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
}
