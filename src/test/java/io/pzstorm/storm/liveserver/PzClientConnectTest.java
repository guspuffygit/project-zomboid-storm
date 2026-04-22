package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a client-mode {@code UdpEngine} running inside the test JVM can complete a RakNet
 * connection handshake with a live Project Zomboid dedicated server and successfully log in through
 * the full handshake, via the reusable {@link LiveServerClient} helper.
 */
@ExtendWith(ServerExtension.class)
class PzClientConnectTest implements IntegrationTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);

    private static LiveServerClient client;

    @BeforeAll
    static void initNativesAndUser() throws Exception {
        LiveServerClient.initClientNativesOnce();
        ServerExtension.createTestCharacter("stormtestclient");
    }

    @AfterAll
    static void shutdownClient() {
        if (client != null) {
            client.close();
        }
        LiveServerClient.shutdownSharedEngine();
    }

    @Test
    void clientConnectsToRunningServer() throws Exception {
        client = new LiveServerClient("stormtestclient", "stormtestpass");
        client.connect(
                "127.0.0.1",
                ServerExtension.TEST_RAKNET_PORT,
                ServerExtension.TEST_SERVER_PASSWORD,
                CONNECT_TIMEOUT);

        Assertions.assertNotNull(client.getConnection(), "expected a live UdpConnection");
        Assertions.assertTrue(client.isFullyConnected(), "expected fully connected state");
    }
}
