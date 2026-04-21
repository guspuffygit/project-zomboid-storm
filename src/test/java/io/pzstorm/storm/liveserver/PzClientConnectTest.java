package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
class PzClientConnectTest implements IntegrationTest {

    private static final String SERVER_PATH_PROPERTY = "storm.server.path";
    private static final String READY_MARKER = "SERVER STARTED";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private static final String TEST_SERVER_NAME = "stormtest";
    private static final int TEST_RAKNET_PORT = 16361;
    private static final int TEST_UDP_PORT = 16362;
    private static final int TEST_HTTP_PORT = 41899;
    private static final String TEST_SERVER_PASSWORD = "";
    private static final String STORM_BOOTSTRAP_JAR =
            "./steamapps/workshop/content/108600/3676481910/mods/storm/bootstrap/storm-bootstrap.jar";

    private static Process serverProcess;
    private static Thread outputReaderThread;
    private static UdpEngine clientEngine;

    @BeforeAll
    static void startServer() throws Exception {
        String serverPath = System.getProperty(SERVER_PATH_PROPERTY);
        Assertions.assertNotNull(serverPath, SERVER_PATH_PROPERTY + " must be set");
        File serverDir = new File(serverPath);

        File buildDir = IntegrationTest.getTemporaryBuildDir(PzClientConnectTest.class);
        Files.createDirectories(buildDir.toPath());
        Path logFile = buildDir.toPath().resolve("server.log");
        Files.deleteIfExists(logFile);
        Files.createFile(logFile);
        Path cacheDir = buildDir.toPath().resolve("zomboid").toAbsolutePath();
        Files.createDirectories(cacheDir);

        ProcessBuilder pb =
                new ProcessBuilder(
                                "./start-server.sh",
                                "-DLOG_LEVEL=debug",
                                "-Dstorm.http.port=" + TEST_HTTP_PORT,
                                "-Dstorm.server=true",
                                "-javaagent:" + STORM_BOOTSTRAP_JAR,
                                "--",
                                "-servername",
                                TEST_SERVER_NAME,
                                "-adminpassword",
                                "stormtest",
                                "-port",
                                Integer.toString(TEST_RAKNET_PORT),
                                "-nosteam",
                                "-udpport",
                                Integer.toString(TEST_UDP_PORT),
                                "-cachedir=" + cacheDir)
                        .directory(serverDir)
                        .redirectErrorStream(true);
        pb.environment().put("HOME", System.getProperty("user.home"));
        serverProcess = pb.start();

        CountDownLatch readyLatch = new CountDownLatch(1);
        AtomicBoolean crashed = new AtomicBoolean(false);
        outputReaderThread =
                new Thread(
                        () -> streamServerOutput(serverProcess, logFile, readyLatch, crashed),
                        "pz-server-output-reader");
        outputReaderThread.setDaemon(true);
        outputReaderThread.start();

        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        boolean ready = false;
        while (Instant.now().isBefore(deadline)) {
            if (readyLatch.await(2, TimeUnit.SECONDS)) {
                ready = true;
                break;
            }
            if (!serverProcess.isAlive()) {
                break;
            }
        }
        Assertions.assertTrue(ready, "server never reached " + READY_MARKER + "; log: " + logFile);

        System.clearProperty("zomboid.steam");
        SteamUtils.init();
        RandStandard.INSTANCE.init();
        RakNetPeerInterface.init();
    }

    @AfterAll
    static void stopAll() {
        if (clientEngine != null) {
            try {
                clientEngine.Shutdown();
            } catch (Throwable ignored) {
            }
        }
        if (serverProcess != null && serverProcess.isAlive()) {
            serverProcess.descendants().forEach(ProcessHandle::destroy);
            serverProcess.destroy();
            try {
                if (!serverProcess.waitFor(30, TimeUnit.SECONDS)) {
                    serverProcess.descendants().forEach(ProcessHandle::destroyForcibly);
                    serverProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (outputReaderThread != null) {
            outputReaderThread.interrupt();
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

        System.out.println("[client] calling Connect to 127.0.0.1:" + TEST_RAKNET_PORT);
        clientEngine.Connect("127.0.0.1", TEST_RAKNET_PORT, TEST_SERVER_PASSWORD, false);

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

    private static void streamServerOutput(
            Process process, Path logFile, CountDownLatch readyLatch, AtomicBoolean crashed) {
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[server] " + line);
                Files.writeString(
                        logFile,
                        line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.APPEND);
                if (line.contains(READY_MARKER)) {
                    readyLatch.countDown();
                }
            }
        } catch (IOException e) {
            crashed.set(true);
        }
    }
}
