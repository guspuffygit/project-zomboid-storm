package io.pzstorm.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Asks a Storm server for its required workshop items over the game's own UDP port, before anything
 * connects. This is the path for servers whose Storm HTTP endpoint is not reachable from the
 * internet — the common case, since that port is meant for operators rather than players.
 *
 * <p>The launcher may not touch Project Zomboid classes, and the query rides Project Zomboid's own
 * RakNet transport, so the actual conversation happens in a CHILD JVM running {@code
 * io.pzstorm.storm.query.StormQueryClient} out of the installed Storm jar, with working directory =
 * the game install (so the game's relative classpath and {@code java.library.path} resolve, and the
 * RakNet natives load). The child prints {@code key=value} lines; this class parses them.
 *
 * <p>Every failure is soft. No Storm on the server, an old Storm, a firewall, no Storm jar to run —
 * all end up as an empty result and the game's own in-game workshop flow still runs.
 */
public final class ServerQuery {

    static final String CHILD_MAIN_CLASS = "io.pzstorm.storm.query.StormQueryClient";
    static final String OK_MARKER = "STORM_QUERY_OK";

    /** Handshake plus reply, per port the child tries. Well above any plausible round trip. */
    private static final long QUERY_TIMEOUT_MILLIS = 12_000L;

    /** Ceiling on the whole child process; the child enforces its own, this is the backstop. */
    private static final long CHILD_TIMEOUT_SECONDS = 60;

    private ServerQuery() {}

    public static final class Result {
        public final String stormVersion;
        public final String gameVersion;
        public final String serverName;
        public final int maxPlayers;
        public final int players;
        public final List<String> workshopItems;
        public final List<String> mods;

        Result(
                String stormVersion,
                String gameVersion,
                String serverName,
                int maxPlayers,
                int players,
                List<String> workshopItems,
                List<String> mods) {
            this.stormVersion = stormVersion;
            this.gameVersion = gameVersion;
            this.serverName = serverName;
            this.maxPlayers = maxPlayers;
            this.players = players;
            this.workshopItems = workshopItems;
            this.mods = mods;
        }
    }

    /** Queries the server, or returns null when the query could not be run or was not answered. */
    public static Result run(LauncherConfig config, ServerProfile profile)
            throws InterruptedException {
        Path gameDir = config.resolveGameDir();
        if (gameDir == null) {
            Log.warn("Game directory not found — skipping the server mod query.");
            return null;
        }
        Path stormLibDir = stormLibDir(config, gameDir);
        if (stormLibDir == null) {
            Log.warn(
                    "Storm jar not found next to the bootstrap directory — skipping the server"
                            + " mod query.");
            return null;
        }
        PzGameJson gameJson;
        try {
            gameJson = PzGameJson.read(gameDir);
        } catch (IOException e) {
            Log.warn("Could not read ProjectZomboid64.json: " + e.getMessage());
            return null;
        }
        Path jvm = config.resolveJvm(gameDir);
        List<String> command =
                buildCommand(
                        jvm,
                        gameJson,
                        stormLibDir,
                        profile.host,
                        profile.port,
                        profile.serverPassword,
                        QUERY_TIMEOUT_MILLIS);

        Log.info("Asking " + profile.connectAddress() + " for its mod list …");
        List<String> lines = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(gameDir.toFile());
            Process child = pb.start();
            drainDiagnostics(child);
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            if (!child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                Log.warn("Server mod query timed out.");
                return null;
            }
        } catch (IOException e) {
            Log.warn("Server mod query could not be started: " + e.getMessage());
            return null;
        }
        Result result = parse(lines);
        if (result == null) {
            Log.info(
                    "No Storm mod list from "
                            + profile.connectAddress()
                            + " (server may not run Storm, or the query port is blocked).");
            return null;
        }
        Log.info(
                "Server reports "
                        + result.workshopItems.size()
                        + " workshop item(s), "
                        + result.mods.size()
                        + " mod id(s) — Storm "
                        + result.stormVersion
                        + ", game "
                        + result.gameVersion
                        + ".");
        return result;
    }

    /**
     * The child's stderr carries its progress notes. Nothing reads it inline, and an unread pipe
     * eventually blocks the child, so it gets its own thread and lands in the launcher log.
     */
    private static void drainDiagnostics(Process child) {
        Thread thread =
                new Thread(
                        () -> {
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    child.getErrorStream(),
                                                    StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    Log.info("  [query] " + line);
                                }
                            } catch (IOException ignored) {
                                // the child is gone; nothing left to report
                            }
                        },
                        "storm-query-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Parses the child's stdout. Returns null unless the child announced a successful query, so a
     * crashed or chatty child can never be mistaken for "this server requires no mods".
     */
    static Result parse(List<String> lines) {
        boolean ok = false;
        String stormVersion = "";
        String gameVersion = "";
        String serverName = "";
        int maxPlayers = 0;
        int players = 0;
        List<String> workshopItems = new ArrayList<>();
        List<String> mods = new ArrayList<>();

        for (String line : lines) {
            if (line.equals(OK_MARKER)) {
                ok = true;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            switch (key) {
                case "stormVersion":
                    stormVersion = value;
                    break;
                case "gameVersion":
                    gameVersion = value;
                    break;
                case "serverName":
                    serverName = value;
                    break;
                case "maxPlayers":
                    maxPlayers = parseInt(value);
                    break;
                case "players":
                    players = parseInt(value);
                    break;
                case "workshop":
                    // A server can name anything it likes; only real ids reach Steam.
                    if (value.matches("\\d{1,20}")) {
                        workshopItems.add(value);
                    } else {
                        Log.warn("Ignoring malformed workshop id from server: " + value);
                    }
                    break;
                case "mod":
                    mods.add(value);
                    break;
                default:
                    break;
            }
        }
        if (!ok) {
            return null;
        }
        return new Result(
                stormVersion, gameVersion, serverName, maxPlayers, players, workshopItems, mods);
    }

    static List<String> buildCommand(
            Path jvm,
            PzGameJson gameJson,
            Path stormLibDir,
            String host,
            int port,
            String serverPassword,
            long timeoutMillis) {
        boolean windows = GameLaunch.isWindowsJvm(jvm);
        List<String> command = new ArrayList<>();
        command.add(jvm.toString());
        // The game's own vmArgs carry java.library.path, which is what lets the RakNet natives
        // load; the child clears zomboid.steam itself so the connect stays a plain UDP one.
        command.addAll(gameJson.effectiveVmArgs(windows ? "Windows" : "", "10.0.99999"));
        command.add("-cp");
        List<String> classpath = new ArrayList<>(gameJson.classpath);
        classpath.add(GameLaunch.pathArgFor(jvm, stormLibDir) + (windows ? "\\*" : "/*"));
        command.add(String.join(windows ? ";" : ":", classpath));
        command.add(CHILD_MAIN_CLASS);
        command.add(host);
        command.add(String.valueOf(port));
        command.add(serverPassword);
        command.add(String.valueOf(timeoutMillis));
        return command;
    }

    /** The {@code 42/lib} directory holding {@code storm-<version>.jar} and its deps, or null. */
    static Path stormLibDir(LauncherConfig config, Path gameDir) {
        Path bootstrapDir = config.resolveBootstrapDir(gameDir);
        if (bootstrapDir == null || bootstrapDir.getParent() == null) {
            return null;
        }
        Path libDir = bootstrapDir.getParent().resolve("42").resolve("lib");
        if (!Files.isDirectory(libDir)) {
            return null;
        }
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(libDir, "storm-*.jar")) {
            return jars.iterator().hasNext() ? libDir : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
