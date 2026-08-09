package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameCrashWatchTest {

    /** Verbatim tail of a field report's game.log (log id 5wdvps500y). */
    private static final String NATIVE_OOM_TAIL =
            "LOG  : General      f:0 st:0> shared-descriptor: registered id=1002\n"
                    + "java.lang.OutOfMemoryError\n"
                    + "#\n"
                    + "# There is insufficient memory for the Java Runtime Environment to"
                    + " continue.\n"
                    + "# Native memory allocation (malloc) failed to allocate 5278232 bytes."
                    + " Error detail: Chunk::new\n";

    @TempDir Path tmp;

    @AfterEach
    void tearDown() {
        GameCrashWatch.onAlert(message -> {});
    }

    @Test
    void nativeOomBannerAlertsRegardlessOfExitCode() {
        assertTrue(GameCrashWatch.diedOfMemory(0, NATIVE_OOM_TAIL));
        assertTrue(GameCrashWatch.diedOfMemory(1, NATIVE_OOM_TAIL));
    }

    @Test
    void heapOomAlertsOnlyOnAbnormalExit() {
        String tail = "Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space\n";
        assertTrue(GameCrashWatch.diedOfMemory(1, tail));
        // a caught-and-survived OOM followed by a clean quit is not a crash
        assertFalse(GameCrashWatch.diedOfMemory(0, tail));
    }

    @Test
    void cleanRunNeverAlerts() {
        assertFalse(GameCrashWatch.diedOfMemory(0, "LOG: game loading took 51 seconds\n"));
        assertFalse(GameCrashWatch.diedOfMemory(1, "LOG: some unrelated crash\n"));
    }

    @Test
    void inspectReadsTheLogAndRaisesTheAlert() throws IOException {
        Path log = tmp.resolve("game.log");
        Files.write(log, NATIVE_OOM_TAIL.getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> alerted = new AtomicReference<>();
        GameCrashWatch.onAlert(alerted::set);

        GameCrashWatch.inspect(1, log);

        assertEquals(GameCrashWatch.OOM_ALERT, alerted.get());
    }

    @Test
    void inspectStaysQuietOnCleanExitAndMissingLog() throws IOException {
        AtomicReference<String> alerted = new AtomicReference<>();
        GameCrashWatch.onAlert(alerted::set);

        Path log = tmp.resolve("game.log");
        Files.write(log, "LOG: normal shutdown\n".getBytes(StandardCharsets.UTF_8));
        GameCrashWatch.inspect(0, log);
        assertNull(alerted.get());

        // a vanished log must neither alert nor throw
        GameCrashWatch.inspect(1, tmp.resolve("nope.log"));
        assertNull(alerted.get());
    }
}
