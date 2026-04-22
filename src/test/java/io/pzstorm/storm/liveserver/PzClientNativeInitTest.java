package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.io.File;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Validates that the test JVM can load Project Zomboid's native networking libraries (RakNet /
 * ZNetNoSteam) and initialize the Java-side static state that a client-mode {@code UdpEngine}
 * depends on. No server is spawned and no socket is opened; this is the smallest possible probe for
 * the JNI + static-init path that a real two-client collision test will sit on top of.
 *
 * <p>The server install dir (containing {@code natives/libRakNet64.so} and {@code
 * natives/libZNetNoSteam64.so}) is supplied via {@code storm.server.path}. The test relies on
 * {@code LD_LIBRARY_PATH} being set to the server's {@code natives/} and {@code linux64/} dirs by
 * the Gradle {@code test} task — {@link System#loadLibrary(String)} resolves against that.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PzClientNativeInitTest implements IntegrationTest {

    private static final String SERVER_PATH_PROPERTY = "storm.server.path";

    @Test
    @Order(1)
    void serverPathAndNativesExist() {
        String serverPath = System.getProperty(SERVER_PATH_PROPERTY);
        Assertions.assertNotNull(serverPath, SERVER_PATH_PROPERTY + " must be set");

        File nativesDir = new File(serverPath, "natives");
        Assertions.assertTrue(nativesDir.isDirectory(), "missing natives dir: " + nativesDir);

        Assertions.assertTrue(
                new File(nativesDir, "libRakNet64.so").isFile(),
                "missing libRakNet64.so under " + nativesDir);
        Assertions.assertTrue(
                new File(nativesDir, "libZNetNoSteam64.so").isFile(),
                "missing libZNetNoSteam64.so under " + nativesDir);
    }

    @Test
    @Order(2)
    void ldLibraryPathIncludesServerNatives() {
        String ldPath = System.getenv("LD_LIBRARY_PATH");
        Assertions.assertNotNull(
                ldPath, "LD_LIBRARY_PATH must be set by the Gradle test task for JNI to resolve.");

        String serverPath = System.getProperty(SERVER_PATH_PROPERTY);
        String nativesDir = new File(serverPath, "natives").getAbsolutePath();
        Assertions.assertTrue(
                ldPath.contains(nativesDir),
                "LD_LIBRARY_PATH does not include " + nativesDir + " (was: " + ldPath + ")");
    }

    @Test
    @Order(3)
    void loadsRakNetAndZNetNoSteamNatives() {
        Assertions.assertDoesNotThrow(() -> System.loadLibrary("RakNet64"));
        Assertions.assertDoesNotThrow(() -> System.loadLibrary("ZNetNoSteam64"));
    }

    @Test
    @Order(4)
    void steamUtilsInitSucceedsInNoSteamMode() {
        System.clearProperty("zomboid.steam");
        Assertions.assertDoesNotThrow(
                () -> zombie.core.znet.SteamUtils.init(),
                "SteamUtils.init() threw when running in no-steam mode");
    }

    @Test
    @Order(5)
    void rakNetPeerInterfaceInitRecordsMainThread() {
        Assertions.assertDoesNotThrow(() -> zombie.core.raknet.RakNetPeerInterface.init());
    }

    @Test
    @Order(6)
    void canInstantiateClientSideRakNetPeer() {
        zombie.core.raknet.RakNetPeerInterface peer =
                Assertions.assertDoesNotThrow(
                        () -> new zombie.core.raknet.RakNetPeerInterface(),
                        "RakNetPeerInterface constructor threw");
        Assertions.assertDoesNotThrow(
                () -> peer.Init(false),
                "RakNetPeerInterface.Init(false) threw — native bridge not wired up");
    }

    @Test
    @Order(7)
    void canInitRandStandard() {
        Assertions.assertDoesNotThrow(() -> zombie.core.random.RandStandard.INSTANCE.init());
    }

    @Test
    @Order(8)
    void canConstructClientSideUdpEngine() throws Exception {
        int localPort = 30000 + new java.util.Random().nextInt(5000);
        zombie.core.raknet.UdpEngine engine =
                new zombie.core.raknet.UdpEngine(localPort, 0, 1, null, false);
        try {
            Assertions.assertNotNull(engine);
        } finally {
            engine.Shutdown();
        }
    }
}
