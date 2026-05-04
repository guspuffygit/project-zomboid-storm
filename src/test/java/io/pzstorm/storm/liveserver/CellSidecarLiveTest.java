package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.patch.performance.CellAddToProcessObjectFastPatch;
import io.pzstorm.storm.patch.performance.CellAddToProcessObjectRemoveFastPatch;
import io.pzstorm.storm.patch.performance.CellAddToStaticUpdaterFastPatch;
import io.pzstorm.storm.patch.performance.CellProcessIsoObjectFlushPatch;
import io.pzstorm.storm.patch.performance.IsoObjectStaticUpdaterRemoveSubstPatch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Smoke test for the cell-sidecar performance patches ({@link CellAddToProcessObjectFastPatch},
 * {@link CellAddToProcessObjectRemoveFastPatch}, {@link CellAddToStaticUpdaterFastPatch}, {@link
 * CellProcessIsoObjectFlushPatch} and {@link IsoObjectStaticUpdaterRemoveSubstPatch}).
 *
 * <p>These patches rewrite hot methods on {@code IsoCell} and {@code IsoObject}, both of which are
 * loaded eagerly during server world initialisation. By the time the {@code SERVER STARTED} marker
 * fires, every patch has either applied cleanly or surfaced as a {@code Failed to apply
 * transformer} log line — so booting the server is sufficient to validate that the rewritten
 * bytecode passes the JVM verifier on the real {@code projectzomboid.jar} classes.
 *
 * <p>The test asserts (a) every patch logged {@code Successfully applied transformer}, (b) no
 * matching {@code Failed to apply transformer} line, and (c) the server process is still alive
 * after startup — i.e. the patched code didn't trigger a {@code VerifyError} during world init.
 */
@ExtendWith(ServerExtension.class)
class CellSidecarLiveTest implements IntegrationTest {

    private static final List<String> PATCH_NAMES =
            List.of(
                    CellAddToProcessObjectFastPatch.class.getSimpleName(),
                    CellAddToProcessObjectRemoveFastPatch.class.getSimpleName(),
                    CellAddToStaticUpdaterFastPatch.class.getSimpleName(),
                    CellProcessIsoObjectFlushPatch.class.getSimpleName(),
                    IsoObjectStaticUpdaterRemoveSubstPatch.class.getSimpleName());

    @Test
    void allCellSidecarPatchesAppliedAtServerBoot() throws IOException {
        Path stormLog = ServerExtension.getStormMainLogFile();
        Assertions.assertNotNull(stormLog, "ServerExtension did not configure Storm log path");
        Assertions.assertTrue(
                Files.exists(stormLog),
                "Expected Storm main.log at " + stormLog + " but it was not created");
        String contents = Files.readString(stormLog, StandardCharsets.UTF_8);

        for (String patchName : PATCH_NAMES) {
            String successMarker = "Successfully applied transformer " + patchName;
            String failureMarker = "Failed to apply transformer " + patchName;

            Assertions.assertTrue(
                    contents.contains(successMarker),
                    () ->
                            "Storm main.log missing '"
                                    + successMarker
                                    + "'. The target class may not have been loaded during boot,"
                                    + " or the patch failed to install. See log: "
                                    + stormLog);
            Assertions.assertFalse(
                    contents.contains(failureMarker),
                    () ->
                            "Storm main.log contains '"
                                    + failureMarker
                                    + "' — patch was registered but threw during transform."
                                    + " See log: "
                                    + stormLog);
        }

        Process serverProcess = ServerExtension.getServerProcess();
        Assertions.assertNotNull(serverProcess);
        Assertions.assertTrue(
                serverProcess.isAlive(),
                "Server died after boot — patched bytecode likely tripped a VerifyError or"
                        + " LinkageError once the JVM tried to use it. See log: "
                        + ServerExtension.getLogFile());
    }
}
