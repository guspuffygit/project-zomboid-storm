package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs against the exact schema {@code zombie.savefile.AccountDBHelper} creates. The SQLite driver
 * comes from the test classpath; production borrows the same driver from projectzomboid.jar.
 */
class VanillaServerDbTest {

    @TempDir Path tempDir;

    private Path createDatabase() throws Exception {
        Path db = tempDir.resolve("db").resolve("ServerListSteam.db");
        Files.createDirectories(db.getParent());
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement statement = conn.createStatement()) {
            statement.execute(VanillaServerDb.SERVER_DDL);
            statement.execute(VanillaServerDb.ACCOUNT_DDL);
        }
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

    private static Driver driver() {
        VanillaServerDb.BorrowedDriver borrowed = VanillaServerDb.loadDriver(null);
        assertNotNull(borrowed, "sqlite-jdbc must be on the test classpath");
        return borrowed.driver;
    }

    private static List<ServerProfile> read(Path db) throws Exception {
        return VanillaServerDb.readProfiles(db, driver());
    }

    private static String queryString(Path db, String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement statement = conn.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static int queryInt(Path db, String sql) throws Exception {
        String value = queryString(db, sql);
        return value == null ? -1 : Integer.parseInt(value);
    }

    @Test
    void readsEveryCharacterMostRecentlyUsedFirst() throws Exception {
        Path db = createDatabase();
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
        assertTrue(p.inGameDb);
        assertEquals(1, p.dbServerId);
        assertTrue(p.dbAccountId > 0);
        assertEquals("MiddleChild", profiles.get(1).username);
        assertEquals("OldTimer", profiles.get(2).username);
    }

    @Test
    void passwordNotSavedInGameIsNotCarriedOver() throws Exception {
        Path db = createDatabase();
        insertServer(db, 1, "storm", "localhost", 16261, "");
        insertAccount(db, 1, "Gus", "typedButNotSaved", 0, "2026-08-01 10:00:00");

        ServerProfile p = read(db).get(0);

        assertEquals("Gus", p.username);
        assertEquals("", p.accountPassword);
        assertFalse(p.autoConnect);
    }

    @Test
    void serverWithoutAccountsStillReads() throws Exception {
        Path db = createDatabase();
        insertServer(db, 1, "bare", "10.0.0.1", 16261, "");

        ServerProfile p = read(db).get(0);

        assertEquals("bare", p.name);
        assertEquals("", p.username);
        assertEquals(-1, p.dbAccountId);
        assertFalse(p.autoConnect);
    }

    @Test
    void characterWithNullLastLogonSortsLast() throws Exception {
        Path db = createDatabase();
        insertServer(db, 1, "s", "10.0.0.1", 16261, "");
        insertAccount(db, 1, "NeverLoggedOn", "a", 1, null);
        insertAccount(db, 1, "LoggedOn", "b", 1, "2026-01-01 00:00:00");

        List<ServerProfile> profiles = read(db);

        assertEquals(2, profiles.size());
        assertEquals("LoggedOn", profiles.get(0).username);
        assertEquals("NeverLoggedOn", profiles.get(1).username);
    }

    @Test
    void missingDatabaseReadsEmpty() throws Exception {
        assertTrue(read(tempDir.resolve("db").resolve("ServerListSteam.db")).isEmpty());
    }

    @Test
    void upsertCreatesDatabaseWithGameSchema() throws Exception {
        Path db = tempDir.resolve("db").resolve("ServerListSteam.db");
        ServerProfile profile = new ServerProfile();
        profile.name = "fresh";
        profile.host = "10.1.1.1";
        profile.port = 16261;
        profile.serverPassword = "sp";
        profile.username = "Gus";
        profile.accountPassword = "ap";

        VanillaServerDb.upsert(db, driver(), profile);

        assertTrue(Files.isRegularFile(db));
        assertTrue(profile.inGameDb);
        assertTrue(profile.dbServerId > 0);
        assertTrue(profile.dbAccountId > 0);
        ServerProfile back = read(db).get(0);
        assertEquals("fresh", back.name);
        assertEquals("sp", back.serverPassword);
        assertEquals("Gus", back.username);
        assertEquals("ap", back.accountPassword);
        // the game's own defaults applied so the row looks exactly like one it wrote itself
        assertEquals(1, queryInt(db, "SELECT authType FROM account"));
        assertEquals(1, queryInt(db, "SELECT isSavePassword FROM account"));
    }

    @Test
    void upsertMatchesExistingRowsInsteadOfDuplicating() throws Exception {
        Path db = createDatabase();
        insertServer(db, 7, "old name", "Play.Example.Org", 16261, "oldpw");
        insertAccount(db, 7, "gus", "oldpwd", 1, "2026-01-01 10:00:00");

        // a fresh profile without row ids — matching must go through ip+port / username
        ServerProfile profile = new ServerProfile();
        profile.name = "new name";
        profile.host = "play.example.org";
        profile.port = 16261;
        profile.serverPassword = "newpw";
        profile.username = "Gus";
        profile.accountPassword = "newpwd";

        VanillaServerDb.upsert(db, driver(), profile);

        assertEquals(1, queryInt(db, "SELECT COUNT(*) FROM server"));
        assertEquals(1, queryInt(db, "SELECT COUNT(*) FROM account"));
        assertEquals(7, profile.dbServerId);
        ServerProfile back = read(db).get(0);
        assertEquals("new name", back.name);
        assertEquals("newpw", back.serverPassword);
        assertEquals("newpwd", back.accountPassword);
    }

    @Test
    void clearingSavedPasswordKeepsTheGamesOwnCopy() throws Exception {
        Path db = createDatabase();
        insertServer(db, 1, "s", "10.0.0.1", 16261, "");
        insertAccount(db, 1, "Gus", "gamesOwnCopy", 1, "2026-01-01 10:00:00");

        ServerProfile profile = read(db).get(0);
        profile.accountPassword = "";
        VanillaServerDb.upsert(db, driver(), profile);

        assertEquals(0, queryInt(db, "SELECT isSavePassword FROM account"));
        // only the opt-in is cleared; the stored password column is the game's to manage
        assertEquals("gamesOwnCopy", queryString(db, "SELECT password FROM account"));
        assertEquals("", read(db).get(0).accountPassword);
    }

    @Test
    void deleteRemovesAccountAndPrunesServerOnceEmpty() throws Exception {
        Path db = createDatabase();
        insertServer(db, 1, "s", "10.0.0.1", 16261, "");
        insertAccount(db, 1, "One", "a", 1, "2026-01-01 10:00:00");
        insertAccount(db, 1, "Two", "b", 1, "2026-02-01 10:00:00");

        List<ServerProfile> profiles = read(db);
        VanillaServerDb.delete(db, driver(), profiles.get(0));
        assertEquals(1, queryInt(db, "SELECT COUNT(*) FROM account"));
        assertEquals(1, queryInt(db, "SELECT COUNT(*) FROM server"));

        VanillaServerDb.delete(db, driver(), profiles.get(1));
        assertEquals(0, queryInt(db, "SELECT COUNT(*) FROM account"));
        assertEquals(0, queryInt(db, "SELECT COUNT(*) FROM server"));
    }
}
