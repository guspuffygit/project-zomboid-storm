package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Asserts that {@code GameServerConnectionCapPatch}'s factory actually executed during {@code
 * GameServer.startServer} on a live boot — the one check a "Successfully applied transformer" log
 * line cannot give you. The factory logs exactly one of two lines depending on the world's {@code
 * MaxPlayers}; either proves the {@code new UdpEngine(...)} call was routed through Storm. Absence
 * of both means the substitution silently stopped matching (e.g. a PZ update moved the call site).
 */
@ExtendWith(ServerExtension.class)
class ConnectionCapLiveTest implements IntegrationTest {

    private static final String RAISED_MARKER = "Storm: RakNet incoming-connection cap raised";
    private static final String VANILLA_MARKER =
            "Storm: RakNet incoming-connection cap left at vanilla";

    @Test
    void capFactoryRanDuringStartServer() throws Exception {
        Path stormLog = ServerExtension.getStormMainLogFile();
        Assertions.assertNotNull(stormLog, "Server not started via ServerExtension");
        Assertions.assertTrue(Files.exists(stormLog), "Storm log missing: " + stormLog);

        String log = Files.readString(stormLog, StandardCharsets.UTF_8);
        Assertions.assertTrue(
                log.contains(RAISED_MARKER) || log.contains(VANILLA_MARKER),
                "Neither '"
                        + RAISED_MARKER
                        + "' nor '"
                        + VANILLA_MARKER
                        + "' appeared in "
                        + stormLog
                        + " — the UdpEngine substitution did not execute during startServer");
    }
}
