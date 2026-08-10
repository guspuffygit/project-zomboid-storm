package io.pzstorm.storm.http;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pzstorm.storm.connection.StormTcpSessionRegistry;
import io.pzstorm.storm.connection.StormTcpSessionRegistry.Session;
import io.pzstorm.storm.util.StormServerTaskQueue;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import zombie.core.Core;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;
import zombie.network.GameServer;
import zombie.savefile.ServerPlayerDB;

/**
 * Serves the saved network-character profiles (vanilla {@code LoadPlayerProfilePacket}: up to 4
 * sequential UDP round trips against the {@code networkPlayers} SQLite table) in a single game-port
 * TCP response for Storm connections.
 *
 * <p>The query must run on the server main thread ({@link StormServerTaskQueue}): vanilla executes
 * it from the packet handler there, and the coop conversion side effect touches the same DB
 * connection.
 */
public class GamePortPlayerProfileEndpoints {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long QUERY_TIMEOUT_SECONDS = 15;
    private static final int MAX_PLAYER_SLOTS = 4;

    @GameHttpEndpoint(path = "/storm/game/player-profiles")
    public static void playerProfiles(HttpRequestEvent event) throws IOException {
        Session session = GamePortHandshakeEndpoints.requireSession(event);
        if (session == null) {
            return;
        }
        UdpConnection connection = StormTcpSessionRegistry.liveConnection(session);
        if (connection == null) {
            event.send(403, "game connection gone");
            return;
        }
        try {
            List<Map<String, Object>> profiles =
                    StormServerTaskQueue.submit(() -> queryProfiles(connection))
                            .get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (profiles == null) {
                // Player DB not open; the vanilla UDP path would go silent here too — a 503
                // makes the client fall back and reproduce vanilla behavior.
                event.send(503, "player database unavailable");
                return;
            }
            event.sendJson(200, MAPPER.writeValueAsString(Map.of("profiles", profiles)));
        } catch (TimeoutException e) {
            event.send(503, "server busy");
        } catch (Exception e) {
            LOGGER.error("Failed to load player profiles for TCP transfer", e);
            event.send(500, "profile query failed");
        }
    }

    /** Mirrors {@code LoadPlayerProfilePacket.processServer}. Main thread only. */
    private static @org.jetbrains.annotations.Nullable List<Map<String, Object>> queryProfiles(
            UdpConnection connection) throws Exception {
        if (ServerPlayerDB.getInstance().conn == null) {
            return null;
        }
        boolean bySteamId = GameServer.coop && SteamUtils.isSteamModeEnabled();
        if (bySteamId) {
            ServerPlayerDB.getInstance()
                    .serverConvertNetworkCharacter(connection.getUserName(), connection.getIDStr());
        }
        String sql =
                bySteamId
                        ? "SELECT id, x, y, z, data, worldversion, isDead FROM networkPlayers WHERE"
                                + " steamid=? AND world=? AND playerIndex=?"
                        : "SELECT id, x, y, z, data, worldversion, isDead FROM networkPlayers WHERE"
                                + " username=? AND world=? AND playerIndex=?";
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (int index = 0; index < MAX_PLAYER_SLOTS; index++) {
            try (PreparedStatement pstmt =
                    ServerPlayerDB.getInstance().conn.prepareStatement(sql)) {
                pstmt.setString(1, bySteamId ? connection.getIDStr() : connection.getUserName());
                pstmt.setString(2, Core.gameSaveWorld);
                pstmt.setInt(3, index);
                ResultSet rs = pstmt.executeQuery();
                if (!rs.next()) {
                    break;
                }
                Map<String, Object> profile = new LinkedHashMap<>();
                profile.put("x", rs.getFloat(2));
                profile.put("y", rs.getFloat(3));
                profile.put("z", rs.getFloat(4));
                profile.put("data", Base64.getEncoder().encodeToString(rs.getBytes(5)));
                profile.put("worldVersion", rs.getInt(6));
                profile.put("isDead", rs.getBoolean(7));
                profiles.add(profile);
            }
        }
        return profiles;
    }
}
