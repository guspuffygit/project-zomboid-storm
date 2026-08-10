package io.pzstorm.launcher;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Remembers the game JVM the launcher spawned so the next launch can retire a leftover one. The
 * launcher starts the game as a windowless {@code javaw}, so a game JVM that survives its own exit
 * (e.g. a stuck non-daemon thread) is invisible to the player — yet it still memory-maps
 * agentlib.dll and the Storm jars, which makes every Steam workshop update of the item fail with
 * "File locked". Identity is pid + process start time (+ executable when the platform reports it),
 * so a recycled pid can never match and an unrelated process is never killed.
 */
public final class GameProcessTracker {

    private GameProcessTracker() {}

    /**
     * The game process spawned this session, if any. Kept so shutdown hooks can take it down when
     * the launcher JVM is asked to exit (e.g. by Steam's Stop button). Cleared by {@link
     * #releaseCurrent()} when the game is meant to outlive the launcher (normal window close).
     */
    private static volatile Process current;

    static Path recordFile() {
        return LauncherPaths.launcherDir().resolve("game-process.properties");
    }

    /** Called right after spawning the game. Best-effort — tracking must never fail a launch. */
    public static void record(Process process) {
        current = process;
        try {
            ProcessHandle handle = process.toHandle();
            Properties props = new Properties();
            props.setProperty("pid", Long.toString(handle.pid()));
            handle.info()
                    .startInstant()
                    .ifPresent(
                            start ->
                                    props.setProperty(
                                            "startMillis", Long.toString(start.toEpochMilli())));
            handle.info().command().ifPresent(command -> props.setProperty("command", command));
            Files.createDirectories(recordFile().getParent());
            try (Writer writer = Files.newBufferedWriter(recordFile(), StandardCharsets.UTF_8)) {
                props.store(writer, null);
            }
        } catch (Exception e) {
            Log.warn("Could not record game process: " + e.getMessage());
        }
    }

    /**
     * Kills the game JVM recorded by a previous spawn if it is verifiably still that same process.
     * Runs right before a new launch: two game instances fight over the Zomboid directory anyway,
     * and a leftover one file-locks the workshop pre-update. A WSL-spawned Windows game records the
     * interop proxy's pid, which can never pass the identity check after that proxy exits — reaping
     * is then a silent no-op rather than a risk.
     */
    public static void reapLeftover() {
        Properties props = new Properties();
        try {
            if (!Files.isRegularFile(recordFile())) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(recordFile(), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            Files.deleteIfExists(recordFile());
        } catch (Exception e) {
            Log.warn("Could not read previous game process record: " + e.getMessage());
            return;
        }
        String pid = props.getProperty("pid");
        String startMillis = props.getProperty("startMillis");
        if (pid == null || startMillis == null) {
            return;
        }
        Optional<ProcessHandle> found;
        try {
            found = ProcessHandle.of(Long.parseLong(pid));
        } catch (NumberFormatException | SecurityException e) {
            return;
        }
        if (found.isEmpty() || !isRecordedProcess(found.get(), props)) {
            return;
        }
        ProcessHandle handle = found.get();
        Log.warn(
                "Previous game JVM (pid "
                        + pid
                        + ") is still running — closing it so it cannot block this launch.");
        handle.destroy();
        try {
            handle.onExit().get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            handle.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.warn("Could not reap previous game process: " + e.getMessage());
        }
    }

    /**
     * Arms the reverse direction of the Steam-Stop coupling: when the tracked game exits (for any
     * reason — user quit, crash, Steam Stop terminating just the game exe), the launcher exits too.
     * Steam tracks the ProjectZomboid64 process by exe name, not the launcher's javaw, so Stop only
     * kills the game — without this the launcher lingers as a headless javaw. Called from the UI
     * path after a successful spawn; headless {@code --join} skips it because the launcher already
     * returns on its own.
     */
    public static void armCloseLauncherOnGameExit() {
        Process process = current;
        if (process == null) {
            return;
        }
        process.onExit()
                .thenRun(
                        () -> {
                            Log.info("Game process exited — closing launcher.");
                            System.exit(0);
                        });
    }

    /**
     * Fired from the launcher JVM's shutdown hook so the tracked game dies with the launcher when
     * Steam's Stop button (or any signal delivered as CTRL_CLOSE_EVENT / SIGTERM) tears the
     * launcher down. On Windows {@link Process#destroy()} is a TerminateProcess; that matches what
     * Steam would do to the game directly if it were the tracked process, so no data-loss surprise
     * is introduced beyond what Stop already implies.
     */
    public static void destroyCurrent() {
        Process process = current;
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            process.destroy();
        } catch (Exception e) {
            Log.warn("Could not close tracked game process on shutdown: " + e.getMessage());
        }
    }

    /**
     * Cleared on normal launcher-window close so the game keeps running independently — matches the
     * pre-existing "you can keep this window open" contract shown in the launcher log.
     */
    public static void releaseCurrent() {
        current = null;
    }

    private static boolean isRecordedProcess(ProcessHandle handle, Properties props) {
        boolean sameStart =
                handle.info()
                        .startInstant()
                        .map(
                                start ->
                                        Long.toString(start.toEpochMilli())
                                                .equals(props.getProperty("startMillis")))
                        .orElse(false);
        String recordedCommand = props.getProperty("command");
        boolean sameCommand =
                recordedCommand == null
                        || handle.info().command().map(recordedCommand::equals).orElse(false);
        return sameStart && sameCommand;
    }
}
