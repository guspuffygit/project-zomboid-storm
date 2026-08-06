package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs against the exact schema {@code zombie.savefile.AccountDBHelper} creates. The SQLite driver
 * comes from the test classpath; production borrows the same driver from projectzomboid.jar.
 */
class VanillaServerImportTest {

    // DDL copied from AccountDBHelper so the fixture matches what the game writes
    private static final String SERVER_DDL =
            "CREATE TABLE server (\n id INTEGER PRIMARY KEY AUTOINCREMENT,\n name TEXT NOT NULL,\n"
                    + " ip TEXT NOT NULL,\n port INTEGER NOT NULL,\n serverPassword TEXT,\n"
                    + " description TEXT,\n mods TEXT,\n icon BLOB,\n banner BLOB,\n"
                    + " panelBackground BLOB,\n screenBackground BLOB,\n lastOnline TEXT,\n"
                    + " lastDataUpdate TEXT\n);";

    private static final String ACCOUNT_DDL =
            "CREATE TABLE account (\n id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                    + " serverId INTEGER NOT NULL,\n playerFirstAndLastName  TEXT,\n"
                    + " username TEXT NOT NULL,\n password TEXT,\n isSavePassword INTEGER DEFAULT 0,\n"
                    + " isUseSteamRelay INTEGER DEFAULT 0,\n authType INTEGER DEFAULT 1,\n"
                    + " icon BLOB,\n timePlayed INTEGER DEFAULT 0,\n lastLogon TEXT,\n"
                    + " FOREIGN KEY (serverId) REFERENCES server (id)\n);";

    @TempDir Path tempDir;

    private Path createDatabase(String name) throws Exception {
        Path db = tempDir.resolve(name);
        Files.createDirectories(db.getParent());
        createFixtureAt(db);
        return db;
    }

    private static void insertServer(
            Path db, int id, String name, String ip, int port, String serverPassword)
            throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                PreparedStatement statement =
                        conn.prepareStatement(
                                "INSERT INTO server (id, name, ip, port, serverPassword)"
                                        + " VALUES (?, ?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, name);
            statement.setString(3, ip);
            statement.setInt(4, port);
            statement.setString(5, serverPassword);
            statement.executeUpdate();
        }
    }

    private static void insertAccount(
            Path db,
            int serverId,
            String username,
            String password,
            int isSavePassword,
            String lastLogon)
            throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                PreparedStatement statement =
                        conn.prepareStatement(
                                "INSERT INTO account (serverId, username, password,"
                                        + " isSavePassword, lastLogon) VALUES (?, ?, ?, ?, ?)")) {
            statement.setInt(1, serverId);
            statement.setString(2, username);
            statement.setString(3, password);
            statement.setInt(4, isSavePassword);
            statement.setString(5, lastLogon);
            statement.executeUpdate();
        }
    }

    private static List<ServerProfile> read(Path db) throws Exception {
        try (VanillaServerImport.BorrowedDriver borrowed = VanillaServerImport.loadDriver(null)) {
            assertNotNull(borrowed, "sqlite-jdbc must be on the test classpath");
            return VanillaServerImport.readProfiles(db, borrowed.driver);
        }
    }

    @Test
    void importsEveryCharacterMostRecentlyUsedFirst() throws Exception {
        Path db = createDatabase("ServerListSteam.db");
        insertServer(db, 1, "After The Fall", "40.160.20.9", 16261, "sekrit");
        insertAccount(db, 1, "OldTimer", "oldpwd", 1, "2026-01-01 10:00:00");
        insertAccount(db, 1, "Fresh", "newpwd", 1, "2026-08-01 10:00:00");
        insertAccount(db, 1, "MiddleChild", "midpwd", 1, "2026-04-01 10:00:00");

        List<ServerProfile> profiles = read(db);

        assertEquals(3, profiles.size());
        ServerProfile p = profiles.get(0);
        assertEquals("After The Fall", p.name);
        assertEquals("40.160.20.9", p.host);
        assertEquals(16261, p.port);
        assertEquals("sekrit", p.serverPassword);
        assertEquals("Fresh", p.username);
        assertEquals("newpwd", p.accountPassword);
        assertTrue(p.autoConnect);
        assertEquals("MiddleChild", profiles.get(1).username);
        assertEquals("midpwd", profiles.get(1).accountPassword);
        assertEquals("OldTimer", profiles.get(2).username);
        assertEquals("After The Fall", profiles.get(2).name);
    }

    @Test
    void passwordNotSavedInGameIsNotCarriedOver() throws Exception {
        Path db = createDatabase("ServerListSteam.db");
        insertServer(db, 1, "storm", "localhost", 16261, "");
        insertAccount(db, 1, "Gus", "typedButNotSaved", 0, "2026-08-01 10:00:00");

        ServerProfile p = read(db).get(0);

        assertEquals("Gus", p.username);
        assertEquals("", p.accountPassword);
        assertFalse(p.autoConnect);
    }

    @Test
    void serverWithoutAccountsStillImports() throws Exception {
        Path db = createDatabase("ServerListSteam.db");
        insertServer(db, 1, "bare", "10.0.0.1", 16261, "");

        ServerProfile p = read(db).get(0);

        assertEquals("bare", p.name);
        assertEquals("", p.username);
        assertFalse(p.autoConnect);
    }

    @Test
    void characterWithNullLastLogonSortsLast() throws Exception {
        Path db = createDatabase("ServerListSteam.db");
        insertServer(db, 1, "s", "10.0.0.1", 16261, "");
        insertAccount(db, 1, "NeverLoggedOn", "a", 1, null);
        insertAccount(db, 1, "LoggedOn", "b", 1, "2026-01-01 00:00:00");

        List<ServerProfile> profiles = read(db);

        assertEquals(2, profiles.size());
        assertEquals("LoggedOn", profiles.get(0).username);
        assertEquals("NeverLoggedOn", profiles.get(1).username);
    }

    @Test
    void mergeSkipsCharactersTheConfigAlreadyHas() {
        LauncherConfig config = new LauncherConfig();
        ServerProfile existing = new ServerProfile();
        existing.name = "mine, hand-tuned";
        existing.host = "40.160.20.9";
        existing.port = 16261;
        existing.username = "Gus";
        existing.extraVmArgs.add("-Dmarker=hand-tuned");
        config.servers.add(existing);

        ServerProfile duplicate = new ServerProfile();
        duplicate.name = "After The Fall";
        duplicate.host = "40.160.20.9";
        duplicate.port = 16261;
        duplicate.username = "gus"; // same character, case-insensitive
        ServerProfile altCharacter = new ServerProfile();
        altCharacter.name = "After The Fall";
        altCharacter.host = "40.160.20.9";
        altCharacter.port = 16261;
        altCharacter.username = "AltGus";
        ServerProfile bare = new ServerProfile();
        bare.name = "After The Fall";
        bare.host = "40.160.20.9";
        bare.port = 16261; // no character: any profile for the address covers it
        ServerProfile fresh = new ServerProfile();
        fresh.name = "One Life";
        fresh.host = "209.192.192.36";
        fresh.port = 16261;

        int added =
                VanillaServerImport.merge(config, List.of(duplicate, altCharacter, bare, fresh));

        assertEquals(2, added);
        assertEquals(3, config.servers.size());
        assertEquals("mine, hand-tuned", config.servers.get(0).name);
        assertEquals(List.of("-Dmarker=hand-tuned"), config.servers.get(0).extraVmArgs);
        assertEquals("AltGus", config.servers.get(1).username);
        assertEquals("One Life", config.servers.get(2).name);
    }

    @Test
    void importIntoReadsSteamDatabase() throws Exception {
        Path zomboidDir = tempDir.resolve("Zomboid");
        Path steamDb = zomboidDir.resolve("db").resolve("ServerListSteam.db");
        Files.createDirectories(steamDb.getParent());
        createFixtureAt(steamDb);
        insertServer(steamDb, 1, "steam name", "10.0.0.1", 16261, "");

        LauncherConfig config = new LauncherConfig();
        int added = VanillaServerImport.importInto(config, zomboidDir);

        assertEquals(1, added);
        assertEquals("steam name", config.servers.get(0).name);
    }

    @Test
    void missingDatabaseImportsNothing() {
        LauncherConfig config = new LauncherConfig();
        assertEquals(0, VanillaServerImport.importInto(config, tempDir.resolve("empty")));
        assertTrue(config.servers.isEmpty());
    }

    private void createFixtureAt(Path db) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement statement = conn.createStatement()) {
            statement.execute(SERVER_DDL);
            statement.execute(ACCOUNT_DDL);
        }
    }
}
