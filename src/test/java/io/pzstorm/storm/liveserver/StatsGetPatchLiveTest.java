package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.patch.performance.StatsGetPatch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end test for {@link StatsGetPatch}. Drives the server-side {@code stormteststatsget}
 * command, which exercises {@code Stats.get(CharacterStat)} on a live JVM and reports per-call
 * thread-allocation bytes after JIT warmup.
 *
 * <p>Two assertions matter:
 *
 * <ol>
 *   <li><b>Correctness</b>: the patched {@code Stats.get} must still return the right value across
 *       set, default-non-zero, default-zero, explicit-zero, and re-set cases. The command runs
 *       these checks server-side and emits {@code correctness=pass} on success.
 *   <li><b>Zero-allocation read path</b>: a {@link Float} box is ≥ 16 bytes (12-byte header +
 *       4-byte float, padded). Vanilla {@code Stats.get} allocates one per call; the patched
 *       version allocates none. We require < 4 bytes/call to leave headroom for JIT-internal noise
 *       (sample-driven counter updates, etc.) while still failing definitively if any boxing slips
 *       back in.
 * </ol>
 *
 * <p>Also verifies the patch installed cleanly at boot via the Storm main.log {@code Successfully
 * applied transformer} marker — same pattern as {@link CellSidecarLiveTest}.
 */
@ExtendWith(ServerExtension.class)
class StatsGetPatchLiveTest implements IntegrationTest {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);
    private static final double MAX_ALLOC_PER_CALL_BYTES = 4.0;

    @Test
    void patchAppliedAtBoot() throws IOException {
        Path stormLog = ServerExtension.getStormMainLogFile();
        Assertions.assertNotNull(stormLog, "ServerExtension did not configure Storm log path");
        Assertions.assertTrue(
                Files.exists(stormLog),
                "Expected Storm main.log at " + stormLog + " but it was not created");
        String contents = Files.readString(stormLog, StandardCharsets.UTF_8);

        String patchName = StatsGetPatch.class.getSimpleName();
        String successMarker = "Successfully applied transformer " + patchName;
        String failureMarker = "Failed to apply transformer " + patchName;

        Assertions.assertTrue(
                contents.contains(successMarker),
                () ->
                        "Storm main.log missing '"
                                + successMarker
                                + "'. zombie.characters.Stats may not have been loaded during"
                                + " boot, or the patch failed to install. See log: "
                                + stormLog);
        Assertions.assertFalse(
                contents.contains(failureMarker),
                () ->
                        "Storm main.log contains '"
                                + failureMarker
                                + "' — patch threw during transform. See log: "
                                + stormLog);
    }

    @Test
    void statsGetIsCorrectAndAllocatesNothing() throws IOException, InterruptedException {
        String result =
                ServerExtension.sendCommandAndAwaitOutput(
                        "stormteststatsget", "RESULT", COMMAND_TIMEOUT);
        Assertions.assertNotNull(result, "stormteststatsget produced no RESULT line");
        System.out.println("[test] " + result);

        Assertions.assertFalse(
                result.contains("RESULT ERROR"),
                "stormteststatsget reported an error: " + result);
        Assertions.assertTrue(
                result.contains("ok=true"),
                "stormteststatsget did not report ok=true: " + result);
        Assertions.assertTrue(
                result.contains("correctness=pass"),
                "Stats.get returned wrong values for one or more cases: " + result);

        double allocPerCall = parseDouble(result, "allocPerCall");
        Assertions.assertTrue(
                allocPerCall < MAX_ALLOC_PER_CALL_BYTES,
                () ->
                        "Stats.get is still allocating on the read path: allocPerCall="
                                + allocPerCall
                                + " bytes (threshold "
                                + MAX_ALLOC_PER_CALL_BYTES
                                + "). A Float box is ≥16 bytes, so anything in that range means the"
                                + " patch isn't taking effect. Full output: "
                                + result);
    }

    private static double parseDouble(String resultLine, String fieldName) {
        Pattern p = Pattern.compile(fieldName + "=([0-9.eE+-]+)");
        Matcher m = p.matcher(resultLine);
        Assertions.assertTrue(m.find(), "could not parse " + fieldName + " from: " + resultLine);
        return Double.parseDouble(m.group(1));
    }
}
