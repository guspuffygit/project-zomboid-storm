package io.pzstorm.launcher;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs the launcher from a staged copy outside the workshop item so Steam can update the item —
 * launcher included — while the launcher is open.
 *
 * <p>Any open handle inside a workshop item dir makes Steam fail the whole item update, even for
 * byte-identical files, and a JVM holds its {@code -jar} open for the life of the process. So a
 * launcher running in place inside the item can never update the item it ships in — and updating is
 * not optional: servers reject version-skewed clients. The fix is a loop that keeps re-entering the
 * item's own front door until the item stops changing:
 *
 * <pre>
 * [item jar]    copy self to stage/&lt;hash&gt;/, exec the copy, exit   (holds the item; no Steam calls)
 * [staged jar]  wait for the parent to die → DownloadItem own item (zero handles in the item)
 *                 item launcher hash == own hash → settled, run normally
 *                 differs → exec the item jar again, exit          (the new version stages itself)
 * </pre>
 *
 * <p>Each version stages <em>itself</em>, so the loop needs no forward knowledge of what a future
 * launcher ships. The loop condition is a content hash, not Steam's per-item result — Steam reports
 * "updated" even when nothing changed. {@link #MAX_HOPS} guards the pathological case of an item
 * that never converges.
 *
 * <p>Repo/dist builds outside any workshop item never stage and never restart — an explicit custom
 * install is not fought. When staging itself fails (antivirus, full disk), the launcher runs in
 * place with a warning: everything still works except self-update, exactly the pre-staging
 * behavior.
 */
public final class LauncherStage {

    static final String STAGED_FROM_FLAG = "--staged-from=";
    static final String PARENT_PID_FLAG = "--parent-pid=";
    static final String HOP_FLAG = "--stage-hop=";

    /** Restarts before giving up on converging with the item's launcher jar. */
    static final int MAX_HOPS = 3;

    static final String JAR_NAME = "storm-launcher.jar";

    private LauncherStage() {}

    /** Stage flags parsed out of argv; {@link #args} is argv with them stripped. */
    static final class Context {

        final String[] args;

        /** Item jar this staged copy came from; null when running in place (repo/dist/item). */
        final Path stagedFrom;

        final long parentPid;
        final int hop;

        Context(String[] args, Path stagedFrom, long parentPid, int hop) {
            this.args = args;
            this.stagedFrom = stagedFrom;
            this.parentPid = parentPid;
            this.hop = hop;
        }

        boolean staged() {
            return stagedFrom != null;
        }
    }

    static Context parse(String[] args) {
        List<String> rest = new ArrayList<>();
        Path stagedFrom = null;
        long parentPid = -1;
        int hop = 0;
        for (String arg : args) {
            if (arg.startsWith(STAGED_FROM_FLAG)) {
                String value = arg.substring(STAGED_FROM_FLAG.length());
                stagedFrom = value.isEmpty() ? null : Paths.get(value);
            } else if (arg.startsWith(PARENT_PID_FLAG)) {
                parentPid = parseLong(arg.substring(PARENT_PID_FLAG.length()), -1);
            } else if (arg.startsWith(HOP_FLAG)) {
                hop = (int) parseLong(arg.substring(HOP_FLAG.length()), 0);
            } else {
                rest.add(arg);
            }
        }
        return new Context(rest.toArray(new String[0]), stagedFrom, parentPid, hop);
    }

    /**
     * When started from inside a workshop item: copy this jar to the stage dir, re-launch from the
     * copy and exit — the item must reach zero open handles before any Steam update runs. Returns
     * (with a warning logged) only when staging is impossible; the in-place run keeps working,
     * minus self-update.
     */
    static void handOffIfInsideWorkshopItem(Context ctx) {
        if (ctx.staged()) {
            return;
        }
        Path ownJar = WorkshopUpdate.ownJar();
        if (ownJar == null || LauncherConfig.workshopItemIdOf(ownJar) == null) {
            return;
        }
        try {
            Path staged = stage(ownJar);
            spawn(handOffCommand(staged, ownJar, ctx));
            Log.info("Staged launcher at " + staged + " — handing off (this process exits).");
            System.exit(0);
        } catch (Exception e) {
            Log.warn(
                    "Could not stage the launcher outside the workshop item ("
                            + e
                            + ") — running in place; Storm cannot self-update while the launcher"
                            + " is open.");
        }
    }

    /**
     * The mandatory own-item update, before any UI or join: DownloadItem with zero handles held in
     * the item, then either settle (item's launcher jar matches this one) or exec the item's jar —
     * which stages itself and repeats the check.
     */
    static void runStartupUpdate(LauncherConfig config, Context ctx) {
        if (!ctx.staged()) {
            return;
        }
        awaitParentExit(ctx.parentPid);
        String itemId = LauncherConfig.workshopItemIdOf(ctx.stagedFrom);
        if (itemId == null) {
            Log.warn(
                    "Staged from outside a workshop item ("
                            + ctx.stagedFrom
                            + ") — skipping the Storm self-update.");
            return;
        }
        try {
            WorkshopUpdate.run(config, List.of(itemId));
        } catch (IOException e) {
            Log.warn(
                    "Storm self-update failed: "
                            + e.getMessage()
                            + " — continuing with the current version.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        Path ownJar = WorkshopUpdate.ownJar();
        String itemHash = hashOrNull(ctx.stagedFrom);
        String ownHash = hashOrNull(ownJar);
        if (itemHash == null) {
            Log.warn(
                    "No launcher jar in the workshop item after the update ("
                            + ctx.stagedFrom
                            + ") — continuing.");
            gcStaleStages(ownJar);
            return;
        }
        if (!shouldRestart(itemHash, ownHash, ctx.hop)) {
            if (!itemHash.equals(ownHash)) {
                Log.warn(
                        "Launcher update loop did not settle after "
                                + ctx.hop
                                + " restarts — continuing with the current version.");
            }
            gcStaleStages(ownJar);
            return;
        }
        Log.info("Workshop item " + itemId + " ships a different launcher — restarting into it.");
        try {
            spawn(restartCommand(ctx));
        } catch (IOException e) {
            Log.warn(
                    "Could not restart into the updated launcher ("
                            + e.getMessage()
                            + ") — continuing with the current version.");
            return;
        }
        System.exit(0);
    }

    /**
     * Loop condition: content hash, never Steam's per-item result — Steam reports "updated" even
     * when nothing changed on disk.
     */
    static boolean shouldRestart(String itemHash, String ownHash, int hop) {
        return !itemHash.equals(ownHash) && hop < MAX_HOPS;
    }

    /**
     * Copies the jar to {@code stage/<hash>/} unless already there. Content-addressed, so the copy
     * is idempotent and two racing instances converge on the same dir; the loser of the rename race
     * just uses the winner's identical copy.
     */
    static Path stage(Path ownJar) throws IOException {
        String fingerprint = Sha256.of(ownJar).substring(0, 16);
        Path targetDir = LauncherPaths.stageDir().resolve(fingerprint);
        Path target = targetDir.resolve(JAR_NAME);
        if (Files.isRegularFile(target)) {
            return target;
        }
        Files.createDirectories(LauncherPaths.stageDir());
        Path tmp = Files.createTempDirectory(LauncherPaths.stageDir(), ".tmp-");
        Files.copy(ownJar, tmp.resolve(JAR_NAME), StandardCopyOption.REPLACE_EXISTING);
        try {
            move(tmp, targetDir);
        } catch (IOException e) {
            deleteRecursively(tmp);
            if (!Files.isRegularFile(target)) {
                throw e;
            }
        }
        return target;
    }

    /** {@code java -jar <staged> --staged-from=<item jar> --parent-pid=<self> --stage-hop=<hop>} */
    static List<String> handOffCommand(Path stagedJar, Path itemJar, Context ctx) {
        Path jvm = currentJvm();
        List<String> command = new ArrayList<>();
        command.add(jvm.toString());
        command.add("-jar");
        command.add(GameLaunch.pathArgFor(jvm, stagedJar));
        command.add(STAGED_FROM_FLAG + GameLaunch.pathArgFor(jvm, itemJar));
        command.add(PARENT_PID_FLAG + ProcessHandle.current().pid());
        command.add(HOP_FLAG + ctx.hop);
        command.addAll(List.of(ctx.args));
        return command;
    }

    /** {@code java -jar <item jar> --stage-hop=<hop+1>} — fresh entry through the item's door. */
    static List<String> restartCommand(Context ctx) {
        Path jvm = currentJvm();
        List<String> command = new ArrayList<>();
        command.add(jvm.toString());
        command.add("-jar");
        command.add(GameLaunch.pathArgFor(jvm, ctx.stagedFrom));
        command.add(HOP_FLAG + (ctx.hop + 1));
        command.addAll(List.of(ctx.args));
        return command;
    }

    /**
     * The parent still holds the item jar for a moment after spawning us; no Steam call may run
     * before it dies.
     */
    static void awaitParentExit(long pid) {
        if (pid <= 0) {
            return;
        }
        Optional<ProcessHandle> parent = ProcessHandle.of(pid);
        if (parent.isEmpty()) {
            return;
        }
        try {
            parent.get().onExit().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.warn(
                    "Parent launcher (pid "
                            + pid
                            + ") is still alive — the Storm update may fail this once.");
        }
    }

    /**
     * Staged copies pile up one per version; sweep all but the copy in use. Best-effort: a
     * still-running older launcher keeps its dir (delete fails on Windows, is harmless on POSIX)
     * until a later sweep.
     */
    static void gcStaleStages(Path ownJar) {
        Path stageRoot = LauncherPaths.stageDir();
        if (!Files.isDirectory(stageRoot)) {
            return;
        }
        Path keep = ownJar == null ? null : ownJar.toAbsolutePath().normalize().getParent();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(stageRoot)) {
            for (Path dir : dirs) {
                if (keep == null || !dir.toAbsolutePath().normalize().equals(keep)) {
                    deleteRecursively(dir);
                }
            }
        } catch (IOException ignored) {
            // sweep again next start
        }
    }

    /** javaw preferred on Windows so re-launches never flash a console window. */
    static Path currentJvm() {
        Path bin = Paths.get(System.getProperty("java.home"), "bin");
        for (String exe : new String[] {"javaw.exe", "java.exe", "java"}) {
            Path candidate = bin.resolve(exe);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return Paths.get("java");
    }

    private static void spawn(List<String> command) throws IOException {
        Path cwd = LauncherPaths.launcherDir();
        Files.createDirectories(cwd);
        new ProcessBuilder(command).directory(cwd.toFile()).inheritIO().start();
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static String hashOrNull(Path jar) {
        try {
            return jar == null || !Files.isRegularFile(jar) ? null : Sha256.of(jar);
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> tree = Files.walk(root)) {
            tree.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException ignored) {
                                    // in use; a later sweep gets it
                                }
                            });
        } catch (IOException ignored) {
            // in use; a later sweep gets it
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
