package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reconciliation between the game's saved-server database and the launcher.json extras. */
class ServerStoreTest {

    @TempDir Path zomboidDir;

    private Path createDatabase() throws Exception {
        Path db = VanillaServerDb.databaseFile(zomboidDir);
        Files.createDirectories(db.getParent());
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement statement = conn.createStatement()) {
            statement.execute(VanillaServerDb.SERVER_DDL);
            statement.execute(VanillaServerDb.ACCOUNT_DDL);
        }
        return db;
    }

    private void seedServerWithAccount() throws Exception {
        Path db = createDatabase();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (PreparedStatement statement =
                    conn.prepareStatement(
                            "INSERT INTO server (id, name, ip, port, serverPassword)"
                                    + " VALUES (1, 'ATF', 'play.example.org', 16261, 'sp')")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                    conn.prepareStatement(
                            "INSERT INTO account (serverId, username, password, isSavePassword)"
                                    + " VALUES (1, 'Gus', 'pwd', 1)")) {
                statement.executeUpdate();
            }
        }
    }

    private static int count(Path db, String table) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement statement = conn.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    @Test
    void loadJoinsDatabaseRowsWithJsonExtras() throws Exception {
        seedServerWithAccount();
        LauncherConfig config = new LauncherConfig();
        ServerProfile extras = new ServerProfile();
        extras.host = "Play.Example.ORG";
        extras.port = 16261;
        extras.username = "gus";
        extras.autoConnect = false; // creds are complete, so the DB default would be true
        extras.updateWorkshopMods = false;
        extras.extraVmArgs.add("-Dmarker=hand-tuned");
        extras.inGameDb = true;
        config.servers.add(extras);

        ServerStore.load(config, zomboidDir);

        assertEquals(1, config.servers.size());
        ServerProfile p = config.servers.get(0);
        assertEquals("ATF", p.name);
        assertEquals("sp", p.serverPassword);
        assertEquals("Gus", p.username);
        assertEquals("pwd", p.accountPassword);
        assertFalse(p.autoConnect);
        assertFalse(p.updateWorkshopMods);
        assertEquals(List.of("-Dmarker=hand-tuned"), p.extraVmArgs);
        assertTrue(p.inGameDb);
    }

    @Test
    void loadMigratesLegacyCredentialEntriesIntoTheDatabase() throws Exception {
        LauncherConfig config = new LauncherConfig();
        ServerProfile legacy = new ServerProfile();
        legacy.name = "hand-added";
        legacy.host = "10.9.8.7";
        legacy.port = 16262;
        legacy.serverPassword = "sp";
        legacy.username = "Gus";
        legacy.accountPassword = "ap";
        config.servers.add(legacy);

        ServerStore.load(config, zomboidDir);

        Path db = VanillaServerDb.databaseFile(zomboidDir);
        assertTrue(Files.isRegularFile(db));
        assertEquals(1, count(db, "server"));
        assertEquals(1, count(db, "account"));
        assertEquals(1, config.servers.size());
        assertTrue(config.servers.get(0).inGameDb);
        // the migrated credentials never go back into launcher.json
        assertFalse(config.servers.get(0).toMap().containsKey("accountPassword"));
        assertFalse(config.servers.get(0).toMap().containsKey("serverPassword"));

        // second load round-trips through the database instead of re-migrating
        ServerStore.load(config, zomboidDir);
        assertEquals(1, count(db, "server"));
        assertEquals(1, config.servers.size());
        assertEquals("ap", config.servers.get(0).accountPassword);
    }

    @Test
    void loadDropsEntriesTheUserDeletedInGame() throws Exception {
        createDatabase(); // schema only: whatever was there is gone now
        LauncherConfig config = new LauncherConfig();
        ServerProfile stale = new ServerProfile();
        stale.host = "10.0.0.1";
        stale.port = 16261;
        stale.username = "Gus";
        stale.inGameDb = true;
        config.servers.add(stale);

        ServerStore.load(config, zomboidDir);

        assertTrue(config.servers.isEmpty());
    }

    @Test
    void removeDeletesFromConfigAndDatabase() throws Exception {
        seedServerWithAccount();
        LauncherConfig config = new LauncherConfig();
        ServerStore.load(config, zomboidDir);
        assertEquals(1, config.servers.size());

        ServerStore.remove(config, config.servers.get(0), zomboidDir);

        assertTrue(config.servers.isEmpty());
        Path db = VanillaServerDb.databaseFile(zomboidDir);
        assertEquals(0, count(db, "server"));
        assertEquals(0, count(db, "account"));
    }

    @Test
    void saveWritesThroughToTheDatabase() throws Exception {
        createDatabase();
        LauncherConfig config = new LauncherConfig();
        ServerProfile profile = new ServerProfile();
        profile.name = "new";
        profile.host = "10.1.2.3";
        profile.port = 16261;
        profile.username = "Gus";
        profile.accountPassword = "pw";

        ServerStore.save(config, profile, zomboidDir);
        config.servers.add(profile);

        assertTrue(profile.inGameDb);
        LauncherConfig reloaded = new LauncherConfig();
        ServerStore.load(reloaded, zomboidDir);
        assertEquals(1, reloaded.servers.size());
        assertEquals("pw", reloaded.servers.get(0).accountPassword);
    }
}
