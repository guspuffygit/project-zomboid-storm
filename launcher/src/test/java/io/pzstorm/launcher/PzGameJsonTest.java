package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PzGameJsonTest {

    private static final String REAL_SHAPE =
            "{"
                    + "\"mainClass\": \"zombie/gameStates/MainScreenState\","
                    + "\"classpath\": [\".\", \"projectzomboid.jar\"],"
                    + "\"vmArgs\": [\"-Djava.awt.headless=true\", \"-Xmx3072m\"],"
                    + "\"windows\": {"
                    + "  \"6.1\": {\"vmArgs\": [\"-XX:+UseG1GC\"]},"
                    + "  \"10.0.17134\": {\"vmArgs\": [\"-XX:+UseZGC\"]}"
                    + "}}";

    @Test
    void parsesRealShape() {
        PzGameJson json = PzGameJson.parse(REAL_SHAPE);
        assertEquals("zombie.gameStates.MainScreenState", json.mainClass);
        assertEquals(List.of(".", "projectzomboid.jar"), json.classpath);
        assertEquals(2, json.windowsOverlays.size());
    }

    @Test
    void nonWindowsGetsBaseArgsOnly() {
        PzGameJson json = PzGameJson.parse(REAL_SHAPE);
        List<String> args = json.effectiveVmArgs("Linux", "6.6.87");
        assertEquals(List.of("-Djava.awt.headless=true", "-Xmx3072m"), args);
    }

    @Test
    void windows10PicksNewestOverlay() {
        PzGameJson json = PzGameJson.parse(REAL_SHAPE);
        List<String> args = json.effectiveVmArgs("Windows 11", "10.0");
        assertTrue(args.contains("-XX:+UseZGC"), args.toString());
        assertFalse(args.contains("-XX:+UseG1GC"));
    }

    @Test
    void windows7PicksOldOverlay() {
        PzGameJson json = PzGameJson.parse(REAL_SHAPE);
        List<String> args = json.effectiveVmArgs("Windows 7", "6.1");
        assertTrue(args.contains("-XX:+UseG1GC"));
        assertFalse(args.contains("-XX:+UseZGC"));
    }

    @Test
    void versionComparisons() {
        assertTrue(PzGameJson.versionSatisfies("10.0", "10.0.17134"));
        assertTrue(PzGameJson.versionSatisfies("10.0.18000", "10.0.17134"));
        assertFalse(PzGameJson.versionSatisfies("10.0.17000", "10.0.17134"));
        assertFalse(PzGameJson.versionSatisfies("6.1", "10.0.17134"));
        assertTrue(PzGameJson.compareVersions("10.0.17134", "6.1") > 0);
    }
}
