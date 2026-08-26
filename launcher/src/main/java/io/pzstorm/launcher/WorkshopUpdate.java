package io.pzstorm.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        /**
         * True only when the child ran and SteamAPI_Init failed — positive evidence that no Steam
         * client is running (as opposed to {@link #childRan} false for jar-on-disk missing, where
         * Steam's state is unknown). This is the gate for the offline acf repair, which must never
         * edit the file under a live Steam client.
         */
        public final boolean steamUnavailable;

        /**
         * Workshop item ids the child reported as not join-ready (FAILED/STALLED/rejected). Empty
         * when the child never ran (Steam unreachable, jar-on-disk missing) — callers that need
         * per-item detail should check {@link #childRan} first.
         */
        public final Set<String> failedItemIds;

        Result(
                boolean allOk,
                int failures,
                int attempted,
                boolean childRan,
                boolean steamUnavailable,
                Set<String> failedItemIds) {
            this.allOk = allOk;
            this.failures = failures;
            this.attempted = attempted;
            this.childRan = childRan;
            this.steamUnavailable = steamUnavailable;
            this.failedItemIds = Collections.unmodifiableSet(new LinkedHashSet<>(failedItemIds));
        }

        Result(
                boolean allOk,
                int failures,
                int attempted,
                boolean childRan,
                Set<String> failedItemIds) {
            this(allOk, failures, attempted, childRan, false, failedItemIds);
        }

        Result(boolean allOk, int failures, int attempted, boolean childRan) {
            this(allOk, failures, attempted, childRan, Collections.emptySet());
        }
    }

    private static final Pattern FAILED_ITEM_ID =
            Pattern.compile("^item (\\d+) (?:FAILED|STALLED|rejected)\\b");

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
        String startMessage =
                "Updating "
                        + updateItemIds.size()
                        + " workshop item(s) via Steam"
                        + (verifyItemIds.isEmpty()
                                ? ""
                                : " (+" + verifyItemIds.size() + " quick state check(s))")
                        + " …";
        return runChild(config, workshopItemIds, startMessage);
    }

    /**
     * Forces a clean re-acquire of items whose install record Steam refuses to refresh (content
     * matches the published manifest, recorded install timestamp doesn't — see {@link
     * SteamUgc#repairItem}): the child cycles each item's subscription and re-downloads. The caller
     * deletes the stale content directories first so the download is real and Steam commits fresh
     * install metadata.
     */
    public static Result runRepair(LauncherConfig config, List<String> repairItemIds)
            throws IOException, InterruptedException {
        if (repairItemIds.isEmpty()) {
            return new Result(true, 0, 0, false);
        }
        List<String> childIds = new ArrayList<>();
        for (String id : repairItemIds) {
            childIds.add(SteamUpdateChild.REPAIR_PREFIX + id);
        }
        return runChild(
                config,
                childIds,
                "Repairing "
                        + repairItemIds.size()
                        + " workshop item(s) via Steam (subscription cycle + re-download) …");
    }

    private static Result runChild(
            LauncherConfig config, List<String> workshopItemIds, String startMessage)
            throws IOException, InterruptedException {
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

        Log.info(startMessage);
        Process child = pb.start();
        int failures = 0;
        Set<String> failedItemIds = new LinkedHashSet<>();
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
                    Matcher m = FAILED_ITEM_ID.matcher(line);
                    if (m.find()) {
                        failedItemIds.add(m.group(1));
                    }
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
            return new Result(
                    false,
                    workshopItemIds.size(),
                    workshopItemIds.size(),
                    false,
                    true,
                    Collections.emptySet());
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
        return new Result(allOk, failures, workshopItemIds.size(), true, failedItemIds);
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
