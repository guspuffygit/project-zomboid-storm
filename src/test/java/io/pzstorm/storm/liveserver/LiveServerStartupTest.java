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
import org.junit.jupiter.api.Test;

/**
 * Smoke test that boots a real Project Zomboid dedicated server via {@code start-server.sh} and
 * confirms it reaches the {@code *** SERVER STARTED ***} log line.
 *
 * <p>Server path is supplied via the {@code storm.server.path} JVM system property (passed from
 * Gradle using the {@code serverPath} local property). The test uses a dedicated servername, RakNet
 * ports, Storm HTTP port, and cachedir so it can run alongside a user-maintained server without
 * port or state collisions. Startup output is streamed to {@code
 * build/tmp/integrationTest/LiveServerStartupTest/server.log} and simultaneously scanned for the
 * readiness marker.
 */
class LiveServerStartupTest implements IntegrationTest {

    private static final String SERVER_PATH_PROPERTY = "storm.server.path";
    private static final String READY_MARKER = "SERVER STARTED";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    private static final String TEST_SERVER_NAME = "stormtest";
    private static final int TEST_RAKNET_PORT = 16361;
    private static final int TEST_UDP_PORT = 16362;
    private static final int TEST_HTTP_PORT = 41899;
    private static final String STORM_BOOTSTRAP_JAR =
            "./steamapps/workshop/content/108600/3676481910/mods/storm/bootstrap/storm-bootstrap.jar";

    private static Process serverProcess;
    private static Thread outputReaderThread;

    @AfterAll
    static void stopServer() {
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
    void serverStartsAndReachesReadyMarker() throws Exception {
        String serverPath = System.getProperty(SERVER_PATH_PROPERTY);
        Assertions.assertNotNull(
                serverPath,
                "System property "
                        + SERVER_PATH_PROPERTY
                        + " must be set (wire it through from Gradle).");

        File serverDir = new File(serverPath);
        Assertions.assertTrue(serverDir.isDirectory(), "Server dir does not exist: " + serverPath);

        File startScript = new File(serverDir, "start-server.sh");
        Assertions.assertTrue(
                startScript.canExecute(),
                "start-server.sh missing or not executable: " + startScript);

        File bootstrapJar = new File(serverDir, STORM_BOOTSTRAP_JAR);
        Assertions.assertTrue(
                bootstrapJar.isFile(),
                "storm-bootstrap.jar missing at "
                        + bootstrapJar
                        + " — run start-with-args.sh once"
                        + " to populate the workshop download.");

        File buildDir = IntegrationTest.getTemporaryBuildDir(LiveServerStartupTest.class);
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
                crashed.set(true);
                break;
            }
        }

        Assertions.assertFalse(
                crashed.get(),
                "Server process exited before reaching '"
                        + READY_MARKER
                        + "' (exit="
                        + safeExitValue(serverProcess)
                        + "); see log: "
                        + logFile);
        Assertions.assertTrue(
                ready,
                "Server did not log '"
                        + READY_MARKER
                        + "' within "
                        + STARTUP_TIMEOUT
                        + "; see log: "
                        + logFile);
    }

    private static void streamServerOutput(
            Process process, Path logFile, CountDownLatch readyLatch, AtomicBoolean crashed) {
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
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

    private static String safeExitValue(Process p) {
        try {
            return Integer.toString(p.exitValue());
        } catch (IllegalThreadStateException e) {
            return "running";
        }
    }
}
