package io.pzstorm.launcher;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Read/write access to the game's own saved-servers database — the single source of truth for
 * server connection info and account credentials. Whatever is set up here shows up in the in-game
 * server browser and vice versa; the launcher never keeps its own copy of a password.
 *
 * <p>The game ({@code zombie.savefile.AccountDBHelper}) persists every server it has connected to
 * in {@code <Zomboid>/db/ServerListSteam.db}: a {@code server} table (name, ip, port, server access
 * password) and an {@code account} table (per-server usernames, passwords and the isSavePassword
 * opt-in). The account password column holds the game's stored form ({@link PzPasswordHash}), never
 * the plaintext; {@link ServerStore} guarantees profiles are in that form before they get here. The
 * launcher handles Steam connections exclusively, so the non-Steam {@code ServerList.db} is never
 * touched. The DDL below is copied verbatim from AccountDBHelper so a launcher started before the
 * game's first run creates exactly the database the game will adopt.
 *
 * <p>Concurrency: the game opens the database per operation and closes it right after, and the
 * launcher's writes happen before the game process exists, so contention is rare; a busy timeout
 * covers the rest. Reads still go against a private copy in case a running game is mid-write.
 *
 * <p>The launcher ships dependency-free, so the SQLite JDBC driver is borrowed at runtime from the
 * game's own {@code projectzomboid.jar} through an isolated classloader; {@code org.sqlite} is not
 * a game class, so the no-PZ-classes rule holds. Everything here fails soft: no database, no game
 * jar, or an unreadable file degrades to the launcher's own json copy of the list.
 */
public final class VanillaServerDb {

    // DDL copied from AccountDBHelper (IF NOT EXISTS added) so the game adopts our file as its own
    static final String SERVER_DDL =
            "CREATE TABLE IF NOT EXISTS server (\n id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                    + " name TEXT NOT NULL,\n ip TEXT NOT NULL,\n port INTEGER NOT NULL,\n"
                    + " serverPassword TEXT,\n description TEXT,\n mods TEXT,\n icon BLOB,\n"
                    + " banner BLOB,\n panelBackground BLOB,\n screenBackground BLOB,\n"
                    + " lastOnline TEXT,\n lastDataUpdate TEXT\n);";

    static final String ACCOUNT_DDL =
            "CREATE TABLE IF NOT EXISTS account (\n id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                    + " serverId INTEGER NOT NULL,\n playerFirstAndLastName  TEXT,\n"
                    + " username TEXT NOT NULL,\n password TEXT,\n"
                    + " isSavePassword INTEGER DEFAULT 0,\n isUseSteamRelay INTEGER DEFAULT 0,\n"
                    + " authType INTEGER DEFAULT 1,\n icon BLOB,\n timePlayed INTEGER DEFAULT 0,\n"
                    + " lastLogon TEXT,\n FOREIGN KEY (serverId) REFERENCES server (id)\n);";

    // lastLogon is 'yyyy-MM-dd HH:mm:ss' text, so DESC puts the most recently used character
    // first per server (SQLite sorts NULLs last under DESC)
    private static final String READ_QUERY =
            "SELECT s.id AS serverRowId, s.name, s.ip, s.port, s.serverPassword,"
                    + " a.id AS accountRowId, a.username, a.password, a.isSavePassword"
                    + " FROM server s LEFT JOIN account a ON a.serverId = s.id"
                    + " ORDER BY s.id, a.lastLogon DESC";

    private VanillaServerDb() {}

    static Path databaseFile(Path zomboidDir) {
        return zomboidDir.resolve("db").resolve("ServerListSteam.db");
    }

    /**
     * One profile per (server, character): every account the game knows becomes its own entry, the
     * most recently used character first per server. The account password is only carried over when
     * the user opted into saving it in-game; auto-connect defaults on only when the credentials are
     * complete. A server with no accounts still yields one entry with empty credentials. Missing
     * database → empty list.
     */
    static List<ServerProfile> readProfiles(Path dbFile, Driver driver) throws Exception {
        if (!Files.isRegularFile(dbFile)) {
            return new ArrayList<>();
        }
        Path tempDir = Files.createTempDirectory("storm-server-db");
        List<Path> copies = new ArrayList<>();
        try {
            Path copy = copyDatabase(dbFile, tempDir, copies);
            Map<String, ServerProfile> byKey = new LinkedHashMap<>();
            try (Connection conn = driver.connect("jdbc:sqlite:" + copy, new Properties());
                    Statement statement = conn.createStatement();
                    ResultSet rs = statement.executeQuery(READ_QUERY)) {
                while (rs.next()) {
                    String host = rs.getString("ip");
                    if (host == null || host.isEmpty()) {
                        continue;
                    }
                    int port = rs.getInt("port");
                    String username = orEmpty(rs.getString("username"));
                    String key = host.toLowerCase() + ":" + port + ":" + username.toLowerCase();
                    if (byKey.containsKey(key)) {
                        continue;
                    }
                    ServerProfile profile = new ServerProfile();
                    profile.dbServerId = rs.getInt("serverRowId");
                    int accountId = rs.getInt("accountRowId");
                    profile.dbAccountId = rs.wasNull() ? -1 : accountId;
                    profile.name = orEmpty(rs.getString("name"));
                    profile.host = host;
                    profile.port = port;
                    profile.serverPassword = orEmpty(rs.getString("serverPassword"));
                    profile.username = username;
                    profile.accountPassword =
                            rs.getInt("isSavePassword") == 1
                                    ? orEmpty(rs.getString("password"))
                                    : "";
                    profile.autoConnect = !username.isEmpty() && !profile.accountPassword.isEmpty();
                    profile.inGameDb = true;
                    byKey.put(key, profile);
                }
            }
            return new ArrayList<>(byKey.values());
        } finally {
            for (Path copy : copies) {
                Files.deleteIfExists(copy);
            }
            Files.deleteIfExists(tempDir);
        }
    }

    /**
     * Writes the profile into the live database, creating it (with the game's own schema) when
     * missing. The server row is matched by known row id, else by ip+port; the account row by known
     * row id, else by server+username — so edits update in place and never duplicate what the game
     * already has. A saved password sets the game's isSavePassword opt-in; an empty one clears only
     * the opt-in and leaves any password column value the game kept for itself untouched. Updates
     * the profile's row ids and marks it as game-db-backed.
     */
    static void upsert(Path dbFile, Driver driver, ServerProfile profile) throws Exception {
        Files.createDirectories(dbFile.getParent());
        try (Connection conn = open(driver, dbFile)) {
            ensureSchema(conn);
            int serverId = profile.dbServerId;
            if (serverId <= 0) {
                serverId = findServerId(conn, profile.host, profile.port);
            }
            if (serverId > 0) {
                try (PreparedStatement update =
                        conn.prepareStatement(
                                "UPDATE server SET name = ?, ip = ?, port = ?, serverPassword = ?"
                                        + " WHERE id = ?")) {
                    update.setString(1, profile.name);
                    update.setString(2, profile.host);
                    update.setInt(3, profile.port);
                    update.setString(4, profile.serverPassword);
                    update.setInt(5, serverId);
                    update.executeUpdate();
                }
            } else {
                try (PreparedStatement insert =
                        conn.prepareStatement(
                                "INSERT INTO server (name, ip, port, serverPassword)"
                                        + " VALUES (?, ?, ?, ?)",
                                Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, profile.name);
                    insert.setString(2, profile.host);
                    insert.setInt(3, profile.port);
                    insert.setString(4, profile.serverPassword);
                    insert.executeUpdate();
                    serverId = generatedKey(insert);
                }
            }
            profile.dbServerId = serverId;
            if (!profile.username.isEmpty()) {
                upsertAccount(conn, serverId, profile);
            }
            profile.inGameDb = true;
        }
    }

    private static void upsertAccount(Connection conn, int serverId, ServerProfile profile)
            throws SQLException {
        int accountId = profile.dbAccountId;
        if (accountId <= 0) {
            accountId = findAccountId(conn, serverId, profile.username);
        }
        boolean savePassword = !profile.accountPassword.isEmpty();
        if (accountId > 0) {
            String sql =
                    savePassword
                            ? "UPDATE account SET username = ?, password = ?, isSavePassword = 1"
                                    + " WHERE id = ?"
                            : "UPDATE account SET username = ?, isSavePassword = 0 WHERE id = ?";
            try (PreparedStatement update = conn.prepareStatement(sql)) {
                int index = 1;
                update.setString(index++, profile.username);
                if (savePassword) {
                    update.setString(index++, profile.accountPassword);
                }
                update.setInt(index, accountId);
                update.executeUpdate();
            }
        } else {
            try (PreparedStatement insert =
                    conn.prepareStatement(
                            "INSERT INTO account (serverId, username, password, isSavePassword)"
                                    + " VALUES (?, ?, ?, ?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                insert.setInt(1, serverId);
                insert.setString(2, profile.username);
                insert.setString(3, savePassword ? profile.accountPassword : "");
                insert.setInt(4, savePassword ? 1 : 0);
                insert.executeUpdate();
                accountId = generatedKey(insert);
            }
        }
        profile.dbAccountId = accountId;
    }

    /**
     * Removes the profile's account from the game's list; the server row goes too once no accounts
     * reference it, exactly mirroring what deleting the entry in-game does.
     */
    static void delete(Path dbFile, Driver driver, ServerProfile profile) throws Exception {
        if (!Files.isRegularFile(dbFile)) {
            return;
        }
        try (Connection conn = open(driver, dbFile)) {
            ensureSchema(conn);
            int serverId = profile.dbServerId;
            if (serverId <= 0) {
                serverId = findServerId(conn, profile.host, profile.port);
            }
            int accountId = profile.dbAccountId;
            if (accountId <= 0 && serverId > 0 && !profile.username.isEmpty()) {
                accountId = findAccountId(conn, serverId, profile.username);
            }
            if (accountId > 0) {
                try (PreparedStatement statement =
                        conn.prepareStatement("DELETE FROM account WHERE id = ?")) {
                    statement.setInt(1, accountId);
                    statement.executeUpdate();
                }
            }
            if (serverId > 0 && countAccounts(conn, serverId) == 0) {
                try (PreparedStatement statement =
                        conn.prepareStatement("DELETE FROM server WHERE id = ?")) {
                    statement.setInt(1, serverId);
                    statement.executeUpdate();
                }
            }
        }
    }

    private static Connection open(Driver driver, Path dbFile) throws SQLException {
        Connection conn = driver.connect("jdbc:sqlite:" + dbFile, new Properties());
        try (Statement statement = conn.createStatement()) {
            // the game may briefly hold the file mid-operation; wait it out instead of failing
            statement.execute("PRAGMA busy_timeout=3000;");
        }
        return conn;
    }

    private static void ensureSchema(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.executeUpdate(SERVER_DDL);
            statement.executeUpdate(ACCOUNT_DDL);
        }
    }

    private static int findServerId(Connection conn, String host, int port) throws SQLException {
        try (PreparedStatement statement =
                conn.prepareStatement(
                        "SELECT id FROM server WHERE ip = ? COLLATE NOCASE AND port = ?")) {
            statement.setString(1, host);
            statement.setInt(2, port);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static int findAccountId(Connection conn, int serverId, String username)
            throws SQLException {
        try (PreparedStatement statement =
                conn.prepareStatement(
                        "SELECT id FROM account WHERE serverId = ?"
                                + " AND username = ? COLLATE NOCASE")) {
            statement.setInt(1, serverId);
            statement.setString(2, username);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static int countAccounts(Connection conn, int serverId) throws SQLException {
        try (PreparedStatement statement =
                conn.prepareStatement("SELECT COUNT(*) FROM account WHERE serverId = ?")) {
            statement.setInt(1, serverId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static int generatedKey(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.getGeneratedKeys()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /**
     * The game may hold the live database open mid-write, so reads go against a private copy. A hot
     * journal/WAL sidecar is copied along so SQLite can recover a consistent view on the copy.
     */
    private static Path copyDatabase(Path dbFile, Path tempDir, List<Path> copies)
            throws IOException {
        Path copy = tempDir.resolve(dbFile.getFileName().toString());
        Files.copy(dbFile, copy);
        copies.add(copy);
        for (String suffix : new String[] {"-journal", "-wal", "-shm"}) {
            Path sidecar = dbFile.resolveSibling(dbFile.getFileName() + suffix);
            if (Files.isRegularFile(sidecar)) {
                Path sidecarCopy = tempDir.resolve(sidecar.getFileName().toString());
                Files.copy(sidecar, sidecarCopy);
                copies.add(sidecarCopy);
            }
        }
        return copy;
    }

    /**
     * The classpath is tried first (tests ship the driver that way); the shipped launcher is
     * dependency-free, so there it always falls through to the game jar. Returns null when neither
     * source has the driver.
     */
    static BorrowedDriver loadDriver(Path gameJar) {
        try {
            Driver driver =
                    (Driver)
                            Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance();
            return new BorrowedDriver(driver, null);
        } catch (ReflectiveOperationException ignored) {
            // expected in production — borrow the game's copy instead
        }
        if (gameJar == null || !Files.isRegularFile(gameJar)) {
            return null;
        }
        URLClassLoader loader = null;
        try {
            loader =
                    new URLClassLoader(
                            new URL[] {gameJar.toUri().toURL()},
                            VanillaServerDb.class.getClassLoader());
            Driver driver =
                    (Driver)
                            loader.loadClass("org.sqlite.JDBC")
                                    .getDeclaredConstructor()
                                    .newInstance();
            return new BorrowedDriver(driver, loader);
        } catch (Exception e) {
            Log.warn("Could not load the SQLite driver from " + gameJar + ": " + e.getMessage());
            if (loader != null) {
                try {
                    loader.close();
                } catch (IOException ignoredClose) {
                    // nothing left to release
                }
            }
            return null;
        }
    }

    /** A driver plus the classloader that must stay open for as long as the driver is in use. */
    static final class BorrowedDriver implements AutoCloseable {

        final Driver driver;
        private final URLClassLoader loader;

        BorrowedDriver(Driver driver, URLClassLoader loader) {
            this.driver = driver;
            this.loader = loader;
        }

        @Override
        public void close() throws IOException {
            if (loader != null) {
                loader.close();
            }
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
