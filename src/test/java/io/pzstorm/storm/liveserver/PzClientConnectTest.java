package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import zombie.core.raknet.RakNetPeerInterface;
import zombie.core.raknet.UdpEngine;
import zombie.core.random.RandStandard;
import zombie.core.znet.SteamUtils;
import zombie.network.GameClient;

/**
 * Verifies that a client-mode {@link UdpEngine} running inside the test JVM can complete a RakNet
 * connection handshake with a live Project Zomboid dedicated server started on loopback. This is
 * the final building block before a two-client byte-id-collision repro can be written — it proves
 * the test harness can speak RakNet to the server without a full PZ client.
 */
@ExtendWith(ServerExtension.class)
class PzClientConnectTest implements IntegrationTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private static UdpEngine clientEngine;

    @BeforeAll
    static void initClientNatives() {
        System.clearProperty("zomboid.steam");
        SteamUtils.init();
        RandStandard.INSTANCE.init();
        RakNetPeerInterface.init();
    }

    @AfterAll
    static void shutdownClient() {
        if (clientEngine != null) {
            try {
                clientEngine.Shutdown();
            } catch (Throwable ignored) {
            }
        }
    }

    @Test
    void clientConnectsToRunningServer() throws Exception {
        int clientLocalPort = 34567;
        GameClient.client = true;
        clientEngine =
                new UdpEngine(clientLocalPort, 0, 1, null, false) {
                    @Override
                    public void connected() {
                        System.out.println("[client] UdpEngine.connected() fired");
                    }
                };

        java.lang.reflect.Field peerField = UdpEngine.class.getDeclaredField("peer");
        peerField.setAccessible(true);
        RakNetPeerInterface peer = (RakNetPeerInterface) peerField.get(clientEngine);

        java.lang.reflect.Field connectionMapField = UdpEngine.class.getDeclaredField("connectionMap");
        connectionMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Long, zombie.core.raknet.UdpConnection> connectionMap =
                (java.util.Map<Long, zombie.core.raknet.UdpConnection>)
                        connectionMapField.get(clientEngine);

        System.out.println("[client] calling Connect to 127.0.0.1:" + ServerExtension.TEST_RAKNET_PORT);
        clientEngine.Connect(
                "127.0.0.1",
                ServerExtension.TEST_RAKNET_PORT,
                ServerExtension.TEST_SERVER_PASSWORD,
                false);

        Instant deadline = Instant.now().plus(CONNECT_TIMEOUT);
        boolean connected = false;
        long lastLog = 0;
        while (Instant.now().isBefore(deadline)) {
            if (!connectionMap.isEmpty()) {
                connected = true;
                break;
            }
            long now = System.currentTimeMillis();
            if (now - lastLog > 2000) {
                System.out.println(
                        "[client] waiting… connectionMap.size="
                                + connectionMap.size()
                                + " peer.GetConnectionsNumber="
                                + peer.GetConnectionsNumber());
                lastLog = now;
            }
            Thread.sleep(200);
        }
        Assertions.assertTrue(
                connected,
                "No UdpConnection appeared in client engine within "
                        + CONNECT_TIMEOUT
                        + " — handshake never completed. peer.GetConnectionsNumber="
                        + peer.GetConnectionsNumber());
    }
}
