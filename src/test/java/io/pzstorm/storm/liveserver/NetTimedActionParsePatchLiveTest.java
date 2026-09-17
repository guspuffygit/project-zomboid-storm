package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Boots a real dedicated server and verifies that {@code zombie.core.NetTimedAction} received
 * {@code NetTimedActionParsePatch}. The class is loaded at boot as the superclass of {@code
 * NetTimedActionPacket}, so a missing "Successfully applied" line means the patch was linked too
 * early (see {@link NetTimedActionPacketPatchLiveTest}) or failed to weave.
 */
@ExtendWith(ServerExtension.class)
class NetTimedActionParsePatchLiveTest implements IntegrationTest {

    private static final String TARGET = "zombie.core.NetTimedAction";

    @Test
    void parsePatchAppliedAtBoot() throws IOException {
        Path stormLog = ServerExtension.getStormMainLogFile();
        Assertions.assertNotNull(stormLog, "ServerExtension did not configure Storm log path");
        Assertions.assertTrue(Files.exists(stormLog), "Expected Storm main.log at " + stormLog);

        String contents = Files.readString(stormLog, StandardCharsets.UTF_8);
        String successMarker =
                "Successfully applied transformer NetTimedActionParsePatch to class " + TARGET;
        String failureMarker =
                "Failed to apply transformer NetTimedActionParsePatch to class " + TARGET;
        Assertions.assertTrue(
                contents.contains(successMarker),
                () -> "Storm main.log missing '" + successMarker + "'. See log: " + stormLog);
        Assertions.assertFalse(
                contents.contains(failureMarker),
                () -> "Storm main.log contains '" + failureMarker + "'. See log: " + stormLog);
    }
}
