package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Reproduces a {@link zombie.core.NetTimedAction} byte-id collision by logging in two independent
 * clients against the same live server and having each send a raw NetTimedAction packet with the
 * same {@code Action.id} byte. Because a single RakNet peer can only hold one outbound connection
 * per {@code (ip, port)} tuple, the second client is hosted in a child JVM via {@link
 * LiveServerClientProcess}. Both clients therefore use independent RakNet peers and independent
 * real connections to the same server.
 *
 * <p>This scaffold verifies the two-peer harness can deliver two colliding-id packets from two
 * logged-in connections. Assertions on the server-side collision behaviour come in a follow-up once
 * the clients can be driven past login into a fully-connected state where {@code NetTimedAction}
 * packets are routed to {@code ActionManager}.
 */
@ExtendWith(ServerExtension.class)
class ActionByteIdCollisionLiveTest implements IntegrationTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final byte COLLIDING_ACTION_ID = (byte) 7;

    private static LiveServerClient clientA;
    private static LiveServerClientProcess clientB;

    @BeforeAll
    static void initNativesAndUsers() throws Exception {
        LiveServerClient.initClientNativesOnce();
        ServerExtension.createTestCharacter("collisionClientA");
        ServerExtension.createTestCharacter("collisionClientB");
    }

    @AfterAll
    static void shutdownClients() {
        if (clientA != null) {
            clientA.close();
        }
        if (clientB != null) {
            clientB.close();
        }
        LiveServerClient.shutdownSharedEngine();
    }

    @Test
    void twoClientsCanSendCollidingActionByteIds() throws Exception {
        clientA = new LiveServerClient("collisionClientA", "passA");
        clientA.connect(
                "127.0.0.1",
                ServerExtension.TEST_RAKNET_PORT,
                ServerExtension.TEST_SERVER_PASSWORD,
                CONNECT_TIMEOUT);

        Assertions.assertTrue(clientA.isFullyConnected(), "clientA not fully connected");

        clientB = LiveServerClientProcess.spawn("collisionClientB");
        long clientBGuid =
                clientB.connect(
                        "127.0.0.1",
                        ServerExtension.TEST_RAKNET_PORT,
                        ServerExtension.TEST_SERVER_PASSWORD,
                        "collisionClientB",
                        "passB",
                        CONNECT_TIMEOUT);

        Assertions.assertNotNull(clientA.getConnection(), "clientA missing UdpConnection");
        Assertions.assertTrue(clientBGuid != 0L, "clientB did not report a valid server GUID");

        System.out.println(
                "[collision] sending NetTimedAction id="
                        + COLLIDING_ACTION_ID
                        + " from both clients");
        clientA.sendRawNetTimedActionBytes(COLLIDING_ACTION_ID, 1000L);
        clientB.sendRawNetTimedActionBytes(COLLIDING_ACTION_ID, 1000L);

        Thread.sleep(1000);

        Assertions.assertNotNull(
                clientA.getConnection(),
                "clientA was disconnected after sending colliding action byte id");
    }
}
