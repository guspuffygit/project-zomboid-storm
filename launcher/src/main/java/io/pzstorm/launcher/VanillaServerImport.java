package io.pzstorm.launcher;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * One-way import of the game's own saved-servers database into launcher profiles, so servers
 * already set up in-game don't have to be retyped.
 *
 * <p>The game ({@code zombie.savefile.AccountDBHelper}) persists every server it has connected to
 * in {@code <Zomboid>/db/ServerListSteam.db}: a {@code server} table (name, ip, port, server access
 * password) and an {@code account} table (per-server usernames and — when the user opted in via
 * isSavePassword — plain-text account passwords).
 *
 * <p>The launcher ships dependency-free, so the SQLite JDBC driver is borrowed at runtime from the
 * game's own {@code projectzomboid.jar} through an isolated classloader; {@code org.sqlite} is not
 * a game class, so the no-PZ-classes rule holds. Everything here fails soft: no database, no game
 * jar, or an unreadable file just means nothing is imported.
 */
public final class VanillaServerImport {

    private static final String QUERY =
            "SELECT s.name, s.ip, s.port, s.serverPassword,"
                    + " a.username, a.password, a.isSavePassword, a.lastLogon"
                    + " FROM server s LEFT JOIN account a ON a.serverId = s.id"
                    + " ORDER BY s.id";

    private VanillaServerImport() {}

    /** Imports into the config; returns the number of profiles added (0 = nothing to do). */
    public static int importInto(LauncherConfig config) {
        return importInto(config, LauncherPaths.zomboidDir());
    }

    static int importInto(LauncherConfig config, Path zomboidDir) {
        List<Path> databases = databases(zomboidDir);
        if (databases.isEmpty()) {
            Log.info("No game saved-server database under " + zomboidDir + " — nothing to import.");
            return 0;
        }
        Path gameDir = config.resolveGameDir();
        Path gameJar = gameDir == null ? null : gameDir.resolve("projectzomboid.jar");
        int added = 0;
        try (BorrowedDriver borrowed = loadDriver(gameJar)) {
            if (borrowed == null) {
                Log.warn(
                        "SQLite driver unavailable (projectzomboid.jar not found)"
                                + " — cannot import the game's saved servers.");
                return 0;
            }
            for (Path database : databases) {
                try {
                    added += merge(config, readProfiles(database, borrowed.driver));
                } catch (Exception e) {
                    Log.warn("Could not read " + database.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException ignored) {
            // only the borrowed classloader's close — the import itself already finished
        }
        Log.info("Imported " + added + " server(s) from the game's saved-server list.");
        return added;
    }

    static List<Path> databases(Path zomboidDir) {
        List<Path> found = new ArrayList<>();
        Path database = zomboidDir.resolve("db").resolve("ServerListSteam.db");
        if (Files.isRegularFile(database)) {
            found.add(database);
        }
        return found;
    }

    /**
     * One profile per distinct ip:port; the most recently used account (max lastLogon, a {@code
     * yyyy-MM-dd HH:mm:ss} string, so text order is time order) fills the credentials. The account
     * password is only carried over when the user already opted into saving it in-game, and
     * autoConnect turns on only when the credentials are complete.
     */
    static List<ServerProfile> readProfiles(Path dbFile, Driver driver) throws Exception {
        Path tempDir = Files.createTempDirectory("storm-server-import");
        List<Path> copies = new ArrayList<>();
        try {
            Path copy = copyDatabase(dbFile, tempDir, copies);
            Map<String, ServerProfile> byAddress = new LinkedHashMap<>();
            Map<String, String> bestLogon = new HashMap<>();
            try (Connection conn = driver.connect("jdbc:sqlite:" + copy, new Properties());
                    Statement statement = conn.createStatement();
                    ResultSet rs = statement.executeQuery(QUERY)) {
                while (rs.next()) {
                    String host = rs.getString("ip");
                    if (host == null || host.isEmpty()) {
                        continue;
                    }
                    int port = rs.getInt("port");
                    String key = host.toLowerCase() + ":" + port;
                    ServerProfile profile = byAddress.get(key);
                    if (profile == null) {
                        profile = new ServerProfile();
                        profile.name = orEmpty(rs.getString("name"));
                        profile.host = host;
                        profile.port = port;
                        profile.serverPassword = orEmpty(rs.getString("serverPassword"));
                        byAddress.put(key, profile);
                    }
                    String username = rs.getString("username");
                    if (username == null || username.isEmpty()) {
                        continue;
                    }
                    String logon = orEmpty(rs.getString("lastLogon"));
                    String best = bestLogon.get(key);
                    if (best == null || logon.compareTo(best) > 0) {
                        bestLogon.put(key, logon);
                        profile.username = username;
                        profile.accountPassword =
                                rs.getInt("isSavePassword") == 1
                                        ? orEmpty(rs.getString("password"))
                                        : "";
                        profile.autoConnect = !profile.accountPassword.isEmpty();
                    }
                }
            }
            return new ArrayList<>(byAddress.values());
        } finally {
            for (Path copy : copies) {
                Files.deleteIfExists(copy);
            }
            Files.deleteIfExists(tempDir);
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
     * Adds profiles for addresses the config doesn't know yet; existing profiles are never touched.
     */
    static int merge(LauncherConfig config, List<ServerProfile> imported) {
        int added = 0;
        for (ServerProfile candidate : imported) {
            if (!hasAddress(config, candidate.host, candidate.port)) {
                config.servers.add(candidate);
                added++;
                Log.info("Imported from game: " + candidate);
            }
        }
        return added;
    }

    private static boolean hasAddress(LauncherConfig config, String host, int port) {
        for (ServerProfile profile : config.servers) {
            if (profile.port == port && profile.host.equalsIgnoreCase(host)) {
                return true;
            }
        }
        return false;
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
                            VanillaServerImport.class.getClassLoader());
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
