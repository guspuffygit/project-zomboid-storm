package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GameInstallStateTest {

    private static String manifest(String stateFlags, String buildId) {
        return "\"AppState\"\n"
                + "{\n"
                + "\t\"appid\"\t\t\"108600\"\n"
                + "\t\"name\"\t\t\"Project Zomboid\"\n"
                + "\t\"StateFlags\"\t\t\""
                + stateFlags
                + "\"\n"
                + "\t\"installdir\"\t\t\"ProjectZomboid\"\n"
                + "\t\"buildid\"\t\t\""
                + buildId
                + "\"\n"
                + "}\n";
    }

    @Test
    void fullyInstalledIsNotPending() {
        GameInstallState state = GameInstallState.parse(manifest("4", "24909800"));

        assertEquals(4, state.stateFlags);
        assertEquals("24909800", state.buildId);
        assertFalse(state.updatePending());
    }

    @Test
    void everyFlavourOfOutstandingSteamWorkCounts() {
        // installed + update required, queued, paused, running, and corrupt files
        assertTrue(GameInstallState.parse(manifest("6", "1")).updatePending());
        assertTrue(GameInstallState.parse(manifest("12", "1")).updatePending());
        assertTrue(GameInstallState.parse(manifest("516", "1")).updatePending());
        assertTrue(GameInstallState.parse(manifest("260", "1")).updatePending());
        assertTrue(GameInstallState.parse(manifest("132", "1")).updatePending());
    }

    @Test
    void unusableManifestsReadNull() {
        assertNull(GameInstallState.parse("\"AppState\"\n{\n\t\"appid\"\t\t\"108600\"\n}\n"));
        assertNull(GameInstallState.parse(manifest("not-a-number", "1")));
        assertNull(GameInstallState.parse(""));
    }

    @Test
    void aManifestWithoutABuildIdStillReports() {
        GameInstallState state =
                GameInstallState.parse("\"AppState\"\n{\n\t\"StateFlags\"\t\t\"6\"\n}\n");

        assertEquals("", state.buildId);
        assertTrue(state.updatePending());
    }
}
