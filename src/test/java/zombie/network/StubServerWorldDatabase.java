package zombie.network;

import java.sql.SQLException;

/**
 * Stub used by {@link io.pzstorm.storm.event.ServerWorldDatabasePatchIntegrationTest} to exercise
 * {@link io.pzstorm.storm.patch.networking.ServerWorldDatabasePatch} without needing a real SQLite
 * connection. Mirrors the {@code banUser}, {@code banIp}, and {@code banSteamID} signatures of
 * {@code zombie.network.ServerWorldDatabase}.
 */
public class StubServerWorldDatabase {

    public String banUser(String username, boolean ban) throws SQLException {
        return "User \"" + username + "\" is now " + (ban ? "banned" : "un-banned");
    }

    public String banIp(String ip, String username, String reason, boolean ban)
            throws SQLException {
        return "IP " + ip + "(" + username + ")  is now " + (ban ? "banned" : "un-banned");
    }

    public String banSteamID(String steamID, String reason, boolean ban) throws SQLException {
        return "SteamID " + steamID + " is now " + (ban ? "banned" : "un-banned");
    }
}
