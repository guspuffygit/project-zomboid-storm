package io.pzstorm.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Parent-side driver for pre-game workshop item updates. The Steamworks calls run in a CHILD JVM
 * ({@code storm-launcher.jar --steam-update <ids…>}) spawned with working directory = the game
 * install: that puts steam_appid.txt in cwd (so SteamAPI initializes as PZ), uses the game's own
 * JRE/steam_api library, and keeps any native failure out of the launcher UI process.
 */
public final class WorkshopUpdate {

    /** Overall ceiling; big first-time downloads report progress and reset stalls. */
    private static final long CHILD_TIMEOUT_MINUTES = 60;

    private WorkshopUpdate() {}

    public static final class Result {
        public final boolean allOk;
        public final int failures;

        Result(boolean allOk, int failures) {
            this.allOk = allOk;
            this.failures = failures;
        }
    }

    public static Result run(LauncherConfig config, List<String> workshopItemIds)
            throws IOException, InterruptedException {
        if (workshopItemIds.isEmpty()) {
            return new Result(true, 0);
        }
        Path gameDir = config.resolveGameDir();
        if (gameDir == null) {
            throw new IOException("Game directory not found — cannot update workshop items");
        }
        Path jvm = config.resolveJvm(gameDir);
        Path ownJar = ownJar();
        if (ownJar == null) {
            Log.warn(
                    "Cannot locate storm-launcher.jar on disk — skipping workshop update"
                            + " (running from classes?)");
            return new Result(false, workshopItemIds.size());
        }

        List<String> command = new ArrayList<>();
        command.add(jvm.toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        GameLaunch.addChildEncodingArgs(command);
        command.add("-jar");
        command.add(GameLaunch.pathArgFor(jvm, ownJar));
        command.add("--steam-update");
        command.addAll(workshopItemIds);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(gameDir.toFile());
        pb.environment().put("SteamAppId", "108600");
        pb.environment().put("SteamGameId", "108600");
        pb.redirectErrorStream(true);

        Log.info("Updating " + workshopItemIds.size() + " workshop item(s) via Steam …");
        Process child = pb.start();
        int failures = 0;
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Log.info("  [steam] " + line);
                if (line.contains("FAILED")
                        || line.contains("STALLED")
                        || line.contains("rejected")) {
                    failures++;
                }
            }
        }
        if (!child.waitFor(CHILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            child.destroyForcibly();
            throw new IOException(
                    "Workshop update timed out after " + CHILD_TIMEOUT_MINUTES + " minutes");
        }
        int exit = child.exitValue();
        if (exit == SteamUpdateChild.EXIT_STEAM_UNAVAILABLE) {
            Log.warn(
                    "Steam is not available — workshop items were NOT updated. The game's"
                            + " own join flow will handle them (may prompt in-game).");
            return new Result(false, workshopItemIds.size());
        }
        boolean allOk = exit == 0 && failures == 0;
        if (allOk) {
            Log.info("All workshop items up to date.");
        } else {
            Log.warn(
                    "Workshop update finished with "
                            + Math.max(failures, 1)
                            + " item(s) not updated (exit "
                            + exit
                            + ").");
        }
        return new Result(allOk, failures);
    }

    static Path ownJar() {
        try {
            URI location =
                    WorkshopUpdate.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI();
            Path path = Paths.get(location);
            return path.toString().endsWith(".jar") ? path : null;
        } catch (Exception e) {
            return null;
        }
    }
}
