package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogReportTest {

    @TempDir Path tmp;

    @BeforeEach
    void setUp() {
        System.setProperty("storm.launcher.zomboidDir", tmp.resolve("Zomboid").toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("storm.launcher.zomboidDir");
        System.clearProperty("storm.launcher.logWebhook");
    }

    @Test
    void webhookAssemblesToOneDiscordUrl() {
        // never assert the token here — the test must not leak what the split hides
        assertTrue(LogReport.WEBHOOK_URL.startsWith("https://discord.com/api/webhooks/"));
        assertFalse(LogReport.WEBHOOK_URL.contains(" "));
    }

    @Test
    void metadataHasMachineFactsAndNoSecrets() {
        LauncherConfig config = new LauncherConfig();
        config.globalVmArgs.add("-Xmx16g");
        ServerProfile profile = new ServerProfile();
        profile.host = "play.example.org";
        profile.serverPassword = "sekrit";
        profile.accountPassword = "hunter2";
        config.servers.add(profile);

        String metadata = LogReport.metadata(config, "abc123defg", "game crashed on join");

        assertTrue(metadata.contains("Log id: abc123defg"));
        assertTrue(metadata.contains("Description: game crashed on join"));
        assertTrue(metadata.contains("OS: " + System.getProperty("os.name")));
        assertTrue(metadata.contains("Architecture: " + System.getProperty("os.arch")));
        assertTrue(metadata.contains("Java: " + System.getProperty("java.version")));
        assertTrue(metadata.contains("CPU cores: "));
        assertTrue(metadata.contains("System RAM: "));
        assertTrue(metadata.contains("-Xmx16g"));
        assertTrue(metadata.contains("Saved servers: 1"));
        assertFalse(metadata.contains("sekrit"), "server password must never leave the machine");
        assertFalse(metadata.contains("hunter2"), "account password must never leave the machine");
    }

    @Test
    void zipBundlesLauncherGameAndZomboidLogs() throws IOException {
        Files.createDirectories(LauncherPaths.logFile().getParent());
        Files.write(LauncherPaths.logFile(), "launcher says hi".getBytes(StandardCharsets.UTF_8));
        Files.write(LauncherPaths.gameLogFile(), "game stdout".getBytes(StandardCharsets.UTF_8));
        Files.write(
                LauncherPaths.previousGameLogFile(),
                "previous game stdout".getBytes(StandardCharsets.UTF_8));
        Path zomboidDir = LauncherPaths.zomboidDir();
        Files.write(
                zomboidDir.resolve("console.txt"), "pz console".getBytes(StandardCharsets.UTF_8));
        Path logsDir = Files.createDirectories(zomboidDir.resolve("Logs"));
        Files.write(logsDir.resolve("main.log"), "storm main".getBytes(StandardCharsets.UTF_8));
        Files.write(logsDir.resolve("debug.log"), "storm debug".getBytes(StandardCharsets.UTF_8));
        Path stormLogsDir = Files.createDirectories(logsDir.resolve("storm"));
        Files.write(
                stormLogsDir.resolve("main.log"),
                "storm client main".getBytes(StandardCharsets.UTF_8));
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Files.write(
                gameDir.resolve("hs_err_pid12008.log"),
                "jvm fatal error".getBytes(StandardCharsets.UTF_8));
        Files.write(gameDir.resolve("projectzomboid.jar"), new byte[] {1});

        Map<String, byte[]> entries = unzip(LogReport.buildZip("meta", gameDir));

        assertEquals("meta", new String(entries.get("metadata.txt"), StandardCharsets.UTF_8));
        assertEquals(
                "launcher says hi",
                new String(entries.get("launcher/launcher.log"), StandardCharsets.UTF_8));
        assertEquals(
                "game stdout",
                new String(entries.get("launcher/game.log"), StandardCharsets.UTF_8));
        assertEquals(
                "previous game stdout",
                new String(entries.get("launcher/game-prev.log"), StandardCharsets.UTF_8));
        assertEquals(
                "pz console",
                new String(entries.get("zomboid/console.txt"), StandardCharsets.UTF_8));
        assertEquals(
                "storm main",
                new String(entries.get("zomboid/Logs/main.log"), StandardCharsets.UTF_8));
        assertEquals(
                "storm debug",
                new String(entries.get("zomboid/Logs/debug.log"), StandardCharsets.UTF_8));
        assertEquals(
                "storm client main",
                new String(entries.get("zomboid/Logs/storm/main.log"), StandardCharsets.UTF_8));
        assertEquals(
                "jvm fatal error",
                new String(entries.get("hs_err/hs_err_pid12008.log"), StandardCharsets.UTF_8));
    }

    @Test
    void missingLogsStillProduceAZipWithMetadata() throws IOException {
        Map<String, byte[]> entries = unzip(LogReport.buildZip("meta only", null));
        assertEquals(1, entries.size());
        assertEquals("meta only", new String(entries.get("metadata.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void hugeLogsAreTailed() throws IOException {
        byte[] big = new byte[LogReport.TAIL_BYTES * 3];
        Arrays.fill(big, (byte) 'a');
        big[big.length - 1] = 'z';
        Path file = Files.write(tmp.resolve("big.log"), big);

        byte[] tail = LogReport.tail(file);

        assertEquals(LogReport.TAIL_BYTES, tail.length);
        assertEquals('z', tail[tail.length - 1], "tail must keep the END of the log");
    }

    @Test
    void sendPostsMultipartWithContentAndZip() throws Exception {
        Files.createDirectories(LauncherPaths.logFile().getParent());
        Files.write(LauncherPaths.logFile(), "launcher log line".getBytes(StandardCharsets.UTF_8));

        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/webhook",
                exchange -> {
                    contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                    requestBody.set(exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        server.start();
        try {
            System.setProperty(
                    "storm.launcher.logWebhook",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook");

            String logId = LogReport.send(new LauncherConfig(), "it broke");

            assertEquals(10, logId.length());
            assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
            String body = new String(requestBody.get(), StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("name=\"content\""));
            assertTrue(body.contains("Description: it broke"));
            assertTrue(body.contains("Log id: " + logId));
            assertTrue(body.contains("name=\"logs\"; filename=\"logs.zip\""));

            String boundary =
                    contentType.get().substring("multipart/form-data; boundary=".length());
            Map<String, byte[]> entries = unzip(extractZipPart(requestBody.get(), boundary));
            assertNotNull(entries.get("metadata.txt"));
            assertEquals(
                    "launcher log line",
                    new String(entries.get("launcher/launcher.log"), StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedUploadThrowsWithStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/webhook",
                exchange -> {
                    exchange.sendResponseHeaders(429, -1);
                    exchange.close();
                });
        server.start();
        try {
            System.setProperty(
                    "storm.launcher.logWebhook",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook");
            IOException e =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            IOException.class, () -> LogReport.send(new LauncherConfig(), ""));
            assertTrue(e.getMessage().contains("429"));
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, byte[]> unzip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    /** Pull the raw bytes of the logs.zip part out of the multipart body. */
    private static byte[] extractZipPart(byte[] body, String boundary) {
        byte[] header =
                "Content-Type: application/zip\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int start = indexOf(body, header, 0);
        assertTrue(start >= 0, "zip part missing from multipart body");
        start += header.length;
        byte[] closer = ("\r\n--" + boundary + "--").getBytes(StandardCharsets.ISO_8859_1);
        int end = indexOf(body, closer, start);
        assertTrue(end >= 0, "multipart body missing closing boundary");
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        zip.write(body, start, end - start);
        return zip.toByteArray();
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
