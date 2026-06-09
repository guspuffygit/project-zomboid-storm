package io.pzstorm.storm.liveserver;

import io.pzstorm.storm.IntegrationTest;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Boots a single Project Zomboid dedicated server for the whole test suite and tears it down once
 * all tests have finished. Tests that need a live server should apply this via
 * {@code @ExtendWith(ServerExtension.class)}.
 */
public class ServerExtension
        implements BeforeAllCallback, ExtensionContext.Store.CloseableResource {

    public static final String SERVER_PATH_PROPERTY = "storm.server.path";
    public static final String READY_MARKER = "SERVER STARTED";
    public static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    public static final String TEST_SERVER_NAME = "stormtest";
    public static final int TEST_RAKNET_PORT = 16361;
    public static final int TEST_UDP_PORT = 16362;
    public static final int TEST_HTTP_PORT = 41899;
    public static final int TEST_PROMETHEUS_PORT = 41900;
    public static final String TEST_SERVER_PASSWORD = "";

    /**
     * Non-default value pre-loaded into {@code Storm.ServerFps} via {@code
     * stormtest_SandboxVars.lua} before the server boots. Picked so it differs from the vanilla
     * default of 10, so the boot-apply integration test can distinguish "boot path actually ran"
     * from "gauges still hold their compiled-in defaults".
     */
    public static final int TEST_SERVER_FPS = 20;

    private static final String STORM_BOOTSTRAP_JAR =
            "./steamapps/workshop/content/108600/3676481910/mods/storm/bootstrap/storm-bootstrap.jar";

    private static boolean started = false;
    @Getter private static Process serverProcess;
    private static Thread outputReaderThread;
    @Getter private static Path logFile;
    @Getter private static Path stormMainLogFile;
    @Getter private static Path evalClassesDir;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (started) {
            return;
        }
        started = true;

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

        File buildDir = IntegrationTest.getTemporaryBuildDir(ServerExtension.class);
        Files.createDirectories(buildDir.toPath());
        logFile = buildDir.toPath().resolve("server.log");
        Files.deleteIfExists(logFile);
        Files.createFile(logFile);

        Path stormLogDir = buildDir.toPath().resolve("storm-logs").toAbsolutePath();
        Files.createDirectories(stormLogDir.resolve("storm"));
        stormMainLogFile = stormLogDir.resolve("storm").resolve("main.log");
        Files.deleteIfExists(stormMainLogFile);
        Files.deleteIfExists(stormLogDir.resolve("storm").resolve("debug.log"));

        Path cacheDir = buildDir.toPath().resolve("zomboid").toAbsolutePath();
        Files.createDirectories(cacheDir);

        // -nosteam makes ZomboidFileSystem.getAllModFolders ignore steam workshop paths, so
        // PZ never sees Storm's mod.info / sandbox-options.txt at the workshop location even
        // though the javaagent loads its classes. Mirror Storm into <cacheDir>/mods/ so the
        // "mods" folder path picks it up; the world INI's Mods=storm-core-b42-dev then resolves.
        Path workshopStormDir =
                serverDir
                        .toPath()
                        .resolve("steamapps/workshop/content/108600/3676481910/mods/storm")
                        .toAbsolutePath();
        Path localModDir = cacheDir.resolve("mods").resolve("storm-core-b42-dev");
        Files.createDirectories(localModDir.getParent());
        if (!Files.exists(localModDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(localModDir, workshopStormDir);
        }

        // Hot-reload endpoints are gated behind -Dstorm.hotreload=true. /eval loads a compiled
        // EvalScript from this absolute dir (the server JVM's cwd is the server dir, not here).
        evalClassesDir = buildDir.toPath().resolve("eval-classes").toAbsolutePath();
        Files.createDirectories(evalClassesDir);

        // Pre-populate the world INI with storm enabled before the server boots. PZ creates it
        // on first launch with Mods= empty, then never re-syncs from CLI; without this, Storm's
        // sandbox-options.txt is never loaded and SandboxOptions has no Storm.* entries.
        Path serverIniDir = cacheDir.resolve("Server");
        Files.createDirectories(serverIniDir);
        Path serverIni = serverIniDir.resolve(TEST_SERVER_NAME + ".ini");
        ensureModsLine(serverIni, "storm-core-b42-dev");

        // Pre-populate Storm sandbox state with a non-default ServerFps so the boot-apply path is
        // observable in /metrics. PZ's SandboxOptions.loadServerLuaFile only writes the keys it
        // finds in the file; unspecified options keep the compiled-in defaults from
        // sandbox-options.txt. After loading, PZ immediately rewrites the file with the full merged
        // state (see GameServer.java:1467), so the next boot still sees ServerFps=20.
        Path sandboxVars = serverIniDir.resolve(TEST_SERVER_NAME + "_SandboxVars.lua");
        writeSandboxVarsFile(sandboxVars, TEST_SERVER_FPS);

        ProcessBuilder pb =
                new ProcessBuilder(
                                "./start-server.sh",
                                "-DLOG_LEVEL=debug",
                                "-DSTORM_LOG_DIR=" + stormLogDir,
                                "-Dstorm.testing=true",
                                "-Dstorm.http.port=" + TEST_HTTP_PORT,
                                "-DprometheusPort=" + TEST_PROMETHEUS_PORT,
                                "-Dstorm.hotreload=true",
                                "-Dstorm.hotreload.eval.classes=" + evalClassesDir,
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

        context.getRoot()
                .getStore(ExtensionContext.Namespace.GLOBAL)
                .put("storm-pz-server-guard", this);
    }

    @Override
    public void close() {
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

    /**
     * Sends a console command to the running server via stdin and waits briefly for it to be
     * processed. The server must be started before calling this.
     */
    public static void sendConsoleCommand(String command) throws IOException, InterruptedException {
        Assertions.assertNotNull(serverProcess, "Server not started");
        Assertions.assertTrue(serverProcess.isAlive(), "Server process is not alive");
        BufferedWriter stdin =
                new BufferedWriter(
                        new OutputStreamWriter(
                                serverProcess.getOutputStream(), StandardCharsets.UTF_8));
        System.out.println("[test] sending console command: " + command);
        stdin.write(command);
        stdin.newLine();
        stdin.flush();
        Thread.sleep(500);
    }

    /**
     * Creates a test character for the given username on the running server. The server must be
     * {@code Open=true} (the default) so the client auto-registers on first login — do NOT call
     * {@code adduser}, which hashes the password differently than the login packet sends it.
     */
    public static void createTestCharacter(String username)
            throws IOException, InterruptedException {
        sendConsoleCommand("stormcreatechar " + username);
        Thread.sleep(2000);
    }

    /**
     * Sends a console command and waits for a line containing {@code marker} to appear in the
     * server log. Returns the first matching line, or {@code null} if the timeout expires.
     */
    public static String sendCommandAndAwaitOutput(String command, String marker, Duration timeout)
            throws IOException, InterruptedException {
        Assertions.assertNotNull(logFile, "Server log not initialized");
        long logSizeBefore = Files.size(logFile);

        sendConsoleCommand(command);

        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
            try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
                raf.seek(logSizeBefore);
                String line;
                while ((line = raf.readLine()) != null) {
                    if (line.contains(marker)) {
                        return line;
                    }
                }
            }
        }
        return null;
    }

    private static void writeSandboxVarsFile(Path file, int serverFps) throws IOException {
        String lineSep = System.lineSeparator();
        String contents =
                "SandboxVars = {"
                        + lineSep
                        + "    VERSION = 6,"
                        + lineSep
                        + "    Storm = {"
                        + lineSep
                        + "        ServerFps = "
                        + serverFps
                        + ","
                        + lineSep
                        + "    },"
                        + lineSep
                        + "}"
                        + lineSep;
        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }

    private static void ensureModsLine(Path serverIni, String modId) throws IOException {
        if (!Files.exists(serverIni)) {
            Files.writeString(
                    serverIni,
                    "Mods="
                            + modId
                            + System.lineSeparator()
                            + "WorkshopItems="
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            return;
        }
        java.util.List<String> lines = Files.readAllLines(serverIni, StandardCharsets.UTF_8);
        boolean foundMods = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("Mods=")) {
                lines.set(i, "Mods=" + modId);
                foundMods = true;
                break;
            }
        }
        if (!foundMods) {
            lines.add("Mods=" + modId);
        }
        Files.write(serverIni, lines, StandardCharsets.UTF_8);
    }

    private static String safeExitValue(Process p) {
        try {
            return Integer.toString(p.exitValue());
        } catch (IllegalThreadStateException e) {
            return "running";
        }
    }
}
