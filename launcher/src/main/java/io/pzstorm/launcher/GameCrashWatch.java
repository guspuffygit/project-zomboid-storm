package io.pzstorm.launcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Watches a spawned game JVM and raises a player-facing alert when it dies of memory exhaustion. To
 * the player an out-of-memory crash is a silent vanish — the game window just closes — and the only
 * evidence is a log they never read; field reports then say "it crashed when I clicked X" for
 * crashes that had nothing to do with X. The watch reads the game's own stdout log after exit, so
 * it costs nothing while the game runs and can never destabilize it.
 */
public final class GameCrashWatch {

    /** Printed by HotSpot's fatal error handler when a native allocation fails. */
    static final String NATIVE_OOM_MARKER =
            "There is insufficient memory for the Java Runtime Environment";

    /** An uncaught heap OOM that unwound the main thread; fatal only when the JVM then exited. */
    static final String HEAP_OOM_MARKER = "java.lang.OutOfMemoryError";

    static final String OOM_ALERT =
            "Project Zomboid closed because your computer ran out of memory.\n\n"
                    + "CLOSE EVERYTHING ELSE on this PC — web browsers, Discord, streaming"
                    + " apps, other games — then join again.\n\n"
                    + "If it keeps happening, lower Game memory in Settings and use Send Logs"
                    + " to ask for help.";

    /** How the alert reaches the player; the UI registers a dialog, core code stays Swing-free. */
    private static volatile Consumer<String> alertSink = message -> {};

    private GameCrashWatch() {}

    public static void onAlert(Consumer<String> sink) {
        alertSink = sink;
    }

    /**
     * Called right after spawning the game; watching must never fail a launch. The exit handler
     * runs on the common pool (daemon), so an open watch never keeps the launcher alive.
     */
    public static void arm(Process process, Path gameLog) {
        try {
            process.onExit().thenAccept(exited -> inspect(exitCodeOf(exited), gameLog));
        } catch (RuntimeException e) {
            Log.warn("Could not watch the game process: " + e.getMessage());
        }
    }

    private static int exitCodeOf(Process process) {
        try {
            return process.exitValue();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static void inspect(int exitCode, Path gameLog) {
        try {
            String tail = new String(LogReport.tail(gameLog), StandardCharsets.UTF_8);
            if (!diedOfMemory(exitCode, tail)) {
                return;
            }
            Log.warn(
                    "The game JVM ran out of memory (exit code "
                            + exitCode
                            + "). Close other programs before playing; if it keeps happening,"
                            + " lower Game memory in Settings.");
            alertSink.accept(OOM_ALERT);
        } catch (Exception e) {
            Log.warn("Could not inspect the game log after exit: " + e.getMessage());
        }
    }

    /**
     * The native banner alone is proof — HotSpot prints it only while dying. A bare
     * OutOfMemoryError line also matches caught-and-survived errors, so it counts only when the
     * process really exited abnormally. game.log holds a single run (rotated at launch), so a
     * previous crash can never false-positive a clean run.
     */
    static boolean diedOfMemory(int exitCode, String logTail) {
        return logTail.contains(NATIVE_OOM_MARKER)
                || (exitCode != 0 && logTail.contains(HEAP_OOM_MARKER));
    }
}
