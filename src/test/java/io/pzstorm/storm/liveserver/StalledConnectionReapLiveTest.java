package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end proof of the stalled-connection reap: a client that completes the RakNet handshake,
 * sends its username, and then goes silent must be force-disconnected by {@code
 * StalledConnectionReaper} once it outstays the connect budget the test server boots with ({@link
 * ServerExtension#TEST_REAP_CONNECT_BUDGET_MS}).
 *
 * <p>This is exactly the production leak shape: vanilla's only reap is gated on {@code
 * getUserName() == null}, so a username-bearing half-open connection holds its RakNet slot forever
 * and, at scale, wedges every new joiner on "Getting Server Info...".
 */
@ExtendWith(ServerExtension.class)
class StalledConnectionReapLiveTest implements IntegrationTest {

    private static final String REAP_LOG_MARKER = "Storm: reaping stalled connection";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private static LiveServerClient client;

    @AfterAll
    static void shutdownClient() {
        if (client != null) {
            client.close();
        }
        LiveServerClient.shutdownSharedEngine();
    }

    @Test
    void serverReapsConnectionThatStallsAfterLogin() throws Exception {
        LiveServerClient.initClientNativesOnce();

        Path stormLog = ServerExtension.getStormMainLogFile();
        Assertions.assertNotNull(stormLog, "Server not started via ServerExtension");
        long logSizeBefore = Files.exists(stormLog) ? Files.size(stormLog) : 0L;

        client = new LiveServerClient("stormstalluser", "stormstallpass");
        client.connectStalled(
                "127.0.0.1",
                ServerExtension.TEST_RAKNET_PORT,
                ServerExtension.TEST_SERVER_PASSWORD,
                CONNECT_TIMEOUT);

        // Budget + one first-seen stamp granularity + one sweep granularity, then margin.
        Duration reapDeadline =
                Duration.ofMillis(
                                ServerExtension.TEST_REAP_CONNECT_BUDGET_MS
                                        + 2 * ServerExtension.TEST_REAP_SWEEP_INTERVAL_MS)
                        .plusSeconds(45);
        String reapLine = awaitLogLine(stormLog, logSizeBefore, REAP_LOG_MARKER, reapDeadline);

        Assertions.assertNotNull(
                reapLine,
                "Server never logged '"
                        + REAP_LOG_MARKER
                        + "' within "
                        + reapDeadline
                        + " — stalled connection was not reaped");
        Assertions.assertTrue(
                reapLine.contains("stormstalluser"),
                "Reap log line should name the stalled user: " + reapLine);
        System.out.println("[test] observed reap: " + reapLine);
    }

    private static String awaitLogLine(
            Path logFile, long fromOffset, String marker, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Thread.sleep(1000);
            if (!Files.exists(logFile)) {
                continue;
            }
            try (var raf = new java.io.RandomAccessFile(logFile.toFile(), "r")) {
                raf.seek(Math.min(fromOffset, raf.length()));
                String line;
                while ((line = raf.readLine()) != null) {
                    String decoded =
                            new String(
                                    line.getBytes(StandardCharsets.ISO_8859_1),
                                    StandardCharsets.UTF_8);
                    if (decoded.contains(marker)) {
                        return decoded;
                    }
                }
            }
        }
        return null;
    }
}
