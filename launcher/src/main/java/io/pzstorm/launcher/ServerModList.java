package io.pzstorm.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Reads a server's required workshop items straight out of its login response, before the game
 * starts. This is the only source that works against a stock server: {@link ServerQuery} needs
 * Storm on the server, and {@link WorkshopStaleScan} can only refresh items that are already
 * installed.
 *
 * <p>The launcher may not touch Project Zomboid classes, so the conversation happens in a CHILD JVM
 * running {@code io.pzstorm.storm.query.ServerModListProbe} out of the installed Storm jar, with
 * working directory = the game install so the RakNet and Steam natives load. Unlike {@link
 * ServerQuery} this needs Steam mode, because it performs a real login.
 *
 * <p>Credentials go to the child over stdin. Passing them as arguments would publish them to every
 * process listing on the machine.
 *
 * <p>Every failure is soft: no credentials, wrong password, no Steam, no Storm jar to run — all end
 * up as an empty result with the game's own in-game workshop flow still to come.
 */
public final class ServerModList {

    static final String CHILD_MAIN_CLASS = "io.pzstorm.storm.query.ServerModListProbe";
    static final String OK_MARKER = "STORM_MODLIST_OK";

    /** RakNet handshake, login, and a multi-megabyte chunked payload over a real internet hop. */
    private static final long PROBE_TIMEOUT_MILLIS = 30_000L;

    /** Ceiling on the whole child process; the child enforces its own, this is the backstop. */
    private static final long CHILD_TIMEOUT_SECONDS = 75;

    private ServerModList() {}

    public static final class Result {
        public final String gameMap;
        public final int maxPlayers;
        public final List<String> workshopItems;
        public final List<String> mods;

        Result(String gameMap, int maxPlayers, List<String> workshopItems, List<String> mods) {
            this.gameMap = gameMap;
            this.maxPlayers = maxPlayers;
            this.workshopItems = workshopItems;
            this.mods = mods;
        }
    }

    /** The server's mod list, or null when the probe could not be run or was refused. */
    public static Result run(LauncherConfig config, ServerProfile profile)
            throws InterruptedException {
        if (profile.username.isEmpty()) {
            Log.info(
                    "No username configured for "
                            + profile.connectAddress()
                            + " — skipping the server mod list probe.");
            return null;
        }
        Path gameDir = config.resolveGameDir();
        if (gameDir == null) {
            Log.warn("Game directory not found — skipping the server mod list probe.");
            return null;
        }
        Path stormLibDir = ServerQuery.stormLibDir(config, gameDir);
        if (stormLibDir == null) {
            Log.warn("Storm jar not found — skipping the server mod list probe.");
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
                        profile.username,
                        PROBE_TIMEOUT_MILLIS);

        Log.info("Reading the mod list from " + profile.connectAddress() + " …");
        List<String> lines = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(gameDir.toFile());
            Process child = pb.start();
            writeCredentials(child, profile);
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
                Log.warn("Server mod list probe timed out.");
                return null;
            }
        } catch (IOException e) {
            Log.warn("Server mod list probe could not be started: " + e.getMessage());
            return null;
        }
        Result result = parse(lines);
        if (result == null) {
            Log.info(
                    "No mod list from "
                            + profile.connectAddress()
                            + " — the game's own workshop flow will handle items.");
            return null;
        }
        Log.info(
                "Server requires "
                        + result.workshopItems.size()
                        + " workshop item(s) and "
                        + result.mods.size()
                        + " mod(s)"
                        + (result.gameMap.isEmpty() ? "" : " on map " + result.gameMap)
                        + ".");
        return result;
    }

    /**
     * {@link Properties} format so the child gets the password back byte-exact whatever is in it.
     * {@code accountPassword} is the game's stored form ({@link PzPasswordHash}); the child sends
     * it on the wire unchanged. The pipe must be closed: the child blocks reading stdin to
     * end-of-stream.
     */
    private static void writeCredentials(Process child, ServerProfile profile) throws IOException {
        Properties credentials = new Properties();
        credentials.setProperty("accountPassword", profile.accountPassword);
        credentials.setProperty("serverPassword", profile.serverPassword);
        try (OutputStream out = child.getOutputStream();
                Writer writer = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            credentials.store(writer, null);
        }
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
                                    Log.info("  [modlist] " + line);
                                }
                            } catch (IOException ignored) {
                                // the child is gone; nothing left to report
                            }
                        },
                        "storm-modlist-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Parses the child's stdout. Returns null unless the child announced success, so a crashed or
     * chatty child can never be mistaken for "this server requires no mods".
     */
    static Result parse(List<String> lines) {
        boolean ok = false;
        String gameMap = "";
        int maxPlayers = 0;
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
                case "gameMap":
                    gameMap = value;
                    break;
                case "maxPlayers":
                    maxPlayers = parseInt(value);
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
        return ok ? new Result(gameMap, maxPlayers, workshopItems, mods) : null;
    }

    static List<String> buildCommand(
            Path jvm,
            PzGameJson gameJson,
            Path stormLibDir,
            String host,
            int port,
            String username,
            long timeoutMillis) {
        boolean windows = GameLaunch.isWindowsJvm(jvm);
        List<String> command = new ArrayList<>();
        command.add(jvm.toString());
        // The game's own vmArgs carry java.library.path and zomboid.steam, which is what lets the
        // RakNet and Steam natives load.
        command.addAll(gameJson.effectiveVmArgs(windows ? "Windows" : "", "10.0.99999"));
        GameLaunch.addChildEncodingArgs(command);
        command.add("-cp");
        List<String> classpath = new ArrayList<>(gameJson.classpath);
        classpath.add(GameLaunch.pathArgFor(jvm, stormLibDir) + (windows ? "\\*" : "/*"));
        command.add(String.join(windows ? ";" : ":", classpath));
        command.add(CHILD_MAIN_CLASS);
        command.add(host);
        command.add(String.valueOf(port));
        command.add(username);
        command.add(String.valueOf(timeoutMillis));
        return command;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
