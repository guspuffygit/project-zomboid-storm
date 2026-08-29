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
 * Boots a real dedicated server and verifies that {@code
 * zombie.network.packets.NetTimedActionPacket} actually received its transformers.
 *
 * <p>This packet used to be the one entry in {@code PacketEventDispatcher.SUPPORTED_PACKETS} that
 * never got woven: linking {@code NetTimedActionPacketPatch} during transformer registration
 * verifier-loaded the packet class before registration completed, so it was defined raw. The
 * failure was invisible — no error, just missing "Applying transformer" lines and dead {@code
 * NetTimedActionPacketEvent} handlers — which is exactly why this test pins the success markers in
 * a live boot.
 */
@ExtendWith(ServerExtension.class)
class NetTimedActionPacketPatchLiveTest implements IntegrationTest {

    private static final String TARGET = "zombie.network.packets.NetTimedActionPacket";

    @Test
    void netTimedActionPacketTransformersAppliedAtBoot() throws IOException {
        Path stormLog = ServerExtension.getStormMainLogFile();
        Assertions.assertNotNull(stormLog, "ServerExtension did not configure Storm log path");
        Assertions.assertTrue(
                Files.exists(stormLog),
                "Expected Storm main.log at " + stormLog + " but it was not created");
        String contents = Files.readString(stormLog, StandardCharsets.UTF_8);

        for (String patchName : new String[] {"NetTimedActionPacketPatch", "PacketReceivedPatch"}) {
            String successMarker =
                    "Successfully applied transformer " + patchName + " to class " + TARGET;
            String failureMarker =
                    "Failed to apply transformer " + patchName + " to class " + TARGET;

            Assertions.assertTrue(
                    contents.contains(successMarker),
                    () ->
                            "Storm main.log missing '"
                                    + successMarker
                                    + "'. Either the packet class was loaded before its"
                                    + " transformers registered (the registration-time verifier"
                                    + " load bug) or it was never loaded during boot. See log: "
                                    + stormLog);
            Assertions.assertFalse(
                    contents.contains(failureMarker),
                    () ->
                            "Storm main.log contains '"
                                    + failureMarker
                                    + "' — patch threw during transform. See log: "
                                    + stormLog);
        }

        Assertions.assertFalse(
                contents.contains("was already loaded before its transformers were registered"),
                "Storm reported transformer targets loaded before registration — silently dead"
                        + " patches. See log: "
                        + stormLog);
    }
}
