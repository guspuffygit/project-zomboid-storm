package com.sentientsimulations.storm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.IntegrationTest;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loads every transformer target through the real {@code StormClassLoader}, in a child JVM per side
 * because the patch set is fixed at class init by {@code StormEnv.isStormServer()}.
 *
 * <p>{@link AllPatchesIntegrationTest} transforms class bytes outside a class load, so it cannot
 * see a transformer that reaches back into its own target while that target is being defined. A
 * reflective call does that when any method of the reflected class names the target: the JVM
 * re-enters the load and throws {@code ClassCircularityError}, and the game cannot load the class.
 * Client mode runs with {@code storm.experimental.clientperf} so the optional patches load too.
 */
class RealClassLoadIntegrationTest implements IntegrationTest {

    @Test
    void everyClientTargetLoadsThroughTheRealLoader(@TempDir Path dir) throws Exception {
        assertEveryTargetLoads(
                dir,
                "-Dstorm.experimental.clientperf=true",
                "zombie.core.skinnedmodel.advancedanimation.AnimatedModel");
    }

    @Test
    void everyServerTargetLoadsThroughTheRealLoader(@TempDir Path dir) throws Exception {
        assertEveryTargetLoads(
                dir, "-Dstorm.server=true", "zombie.network.packets.character.CreatePlayerPacket");
    }

    private static void assertEveryTargetLoads(Path dir, String sideFlag, String requiredTarget)
            throws IOException, InterruptedException {
        Path output = dir.resolve("probe.log");
        Process process =
                new ProcessBuilder(
                                System.getProperty("java.home")
                                        + File.separator
                                        + "bin"
                                        + File.separator
                                        + "java",
                                "-Duser.home=" + dir,
                                "-Dstorm.testing=true",
                                sideFlag,
                                "--enable-native-access=ALL-UNNAMED",
                                "-cp",
                                System.getProperty("java.class.path"),
                                RealClassLoadProbe.class.getName(),
                                requiredTarget)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        boolean finished = process.waitFor(3, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "probe did not finish");

        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        String results =
                lines.stream()
                        .filter(line -> line.startsWith(RealClassLoadProbe.RESULT_PREFIX))
                        .collect(Collectors.joining("\n"));
        assertEquals(0, process.exitValue(), results);
        assertTrue(results.contains("targets="), "probe printed no result");
    }
}
