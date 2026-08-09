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

        /** Item count handed to the child process. */
        public final int attempted;

        /** Whether the Steamworks child actually ran with Steam reachable. */
        public final boolean childRan;

        Result(boolean allOk, int failures, int attempted, boolean childRan) {
            this.allOk = allOk;
            this.failures = failures;
            this.attempted = attempted;
            this.childRan = childRan;
        }

        /**
         * Steam was reachable yet not one required item came out join-ready — the in-game workshop
         * flow talks to the same stuck Steam client, so launching the game cannot end differently.
         */
        public boolean nothingUpdated() {
            return childRan && attempted > 0 && failures >= attempted;
        }
    }

    public static Result run(LauncherConfig config, List<String> workshopItemIds)
            throws IOException, InterruptedException {
        return run(config, workshopItemIds, List.of());
    }

    /**
     * {@code updateItemIds} get the full per-item DownloadItem confirm; {@code verifyItemIds} were
     * already proven current against the published workshop metadata (see {@link
     * WorkshopStaleScan.Scan#isCurrent}) and only get an instant local state check — the child
     * escalates any of them that turn out not join-ready.
     */
    public static Result run(
            LauncherConfig config, List<String> updateItemIds, List<String> verifyItemIds)
            throws IOException, InterruptedException {
        List<String> workshopItemIds = new ArrayList<>(updateItemIds);
        for (String id : verifyItemIds) {
            workshopItemIds.add(SteamUpdateChild.VERIFY_PREFIX + id);
        }
        if (workshopItemIds.isEmpty()) {
            return new Result(true, 0, 0, false);
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
            return new Result(false, workshopItemIds.size(), workshopItemIds.size(), false);
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

        Log.info(
                "Updating "
                        + updateItemIds.size()
                        + " workshop item(s) via Steam"
                        + (verifyItemIds.isEmpty()
                                ? ""
                                : " (+" + verifyItemIds.size() + " quick state check(s))")
                        + " …");
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
            return new Result(false, workshopItemIds.size(), workshopItemIds.size(), false);
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
        return new Result(allOk, failures, workshopItemIds.size(), true);
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
