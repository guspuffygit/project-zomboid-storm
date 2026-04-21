package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Smoke test that boots a real Project Zomboid dedicated server (via {@link ServerExtension}) and
 * confirms it reaches the {@code *** SERVER STARTED ***} log line.
 *
 * <p>The heavy lifting — launching {@code start-server.sh}, streaming output, waiting for the
 * readiness marker, and shutting the server back down — lives in {@link ServerExtension} so the
 * server can be shared across every live-server integration test without being restarted between
 * test classes.
 */
@ExtendWith(ServerExtension.class)
class LiveServerStartupTest implements IntegrationTest {

    @Test
    void serverStartsAndReachesReadyMarker() {
        Process serverProcess = ServerExtension.getServerProcess();
        Assertions.assertNotNull(serverProcess, "ServerExtension did not start a server process");
        Assertions.assertTrue(
                serverProcess.isAlive(),
                "Server process is not alive; see log: " + ServerExtension.getLogFile());
    }
}
