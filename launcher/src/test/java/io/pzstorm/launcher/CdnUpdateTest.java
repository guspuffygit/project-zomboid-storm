package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CdnUpdateTest {

    @TempDir Path tmp;

    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        System.setProperty("storm.launcher.zomboidDir", tmp.resolve("Zomboid").toString());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        System.clearProperty("storm.launcher.zomboidDir");
    }

    @Test
    void isNewerComparesDottedNumerics() {
        assertTrue(CdnUpdate.isNewer("1.0.1", "1.0.0"));
        assertTrue(CdnUpdate.isNewer("1.10.0", "1.9.9"), "numeric, not lexicographic");
        assertTrue(CdnUpdate.isNewer("2.0", "1.9.9"), "missing segments are zero");
        assertFalse(CdnUpdate.isNewer("1.0.0", "1.0.0"));
        assertFalse(CdnUpdate.isNewer("1.0.0", "1.0"), "trailing zero segment is equal");
        assertFalse(CdnUpdate.isNewer("0.9.9", "1.0.0"));
        assertFalse(CdnUpdate.isNewer("1.0.1", "42.20.2_2.5.1"), "old-format version fails soft");
        assertFalse(CdnUpdate.isNewer("dev", "1.0.0"));
        assertFalse(CdnUpdate.isNewer("1.0.1", null));
        assertFalse(CdnUpdate.isNewer(null, "1.0.0"));
    }

    @Test
    void fetchReadsMetadataHeaders() {
        byte[] jar = "launcher v2".getBytes();
        String sha = Sha256.of(jar);
        serve("/launcher.jar", jar, sha, "1.2.3");

        CdnUpdate.Remote remote = CdnUpdate.fetch(url("/launcher.jar"));

        assertEquals("1.2.3", remote.version());
        assertEquals(sha, remote.sha256());
    }

    @Test
    void fetchIsNullOnMissingObjectMissingMetadataOrNoUrl() {
        serve("/no-meta.jar", "bytes".getBytes(), null, null);
        assertNull(CdnUpdate.fetch(url("/absent.jar")), "404");
        assertNull(CdnUpdate.fetch(url("/no-meta.jar")), "no metadata headers");
        assertNull(CdnUpdate.fetch(""), "unset URL disables the check");
        assertNull(CdnUpdate.fetch(null));
        assertNull(CdnUpdate.fetch("http://127.0.0.1:1/launcher.jar"), "unreachable host");
    }

    @Test
    void downloadStagesContentAddressedAfterHashCheck() throws Exception {
        byte[] jar = "launcher v2".getBytes();
        String sha = Sha256.of(jar);
        serve("/launcher.jar", jar, sha, "1.2.3");

        Path staged = CdnUpdate.download(url("/launcher.jar"), sha);

        assertEquals(LauncherStage.JAR_NAME, staged.getFileName().toString());
        assertEquals(LauncherPaths.stageDir().resolve(sha.substring(0, 16)), staged.getParent());
        assertEquals(sha, Sha256.of(staged));
        try (var entries = Files.list(LauncherPaths.stageDir())) {
            assertEquals(1, entries.count(), "no tmp download leftovers");
        }
    }

    @Test
    void downloadRefusesHashMismatch() {
        byte[] jar = "tampered".getBytes();
        serve("/launcher.jar", jar, null, null);
        String expected = Sha256.of("what was published".getBytes());

        assertThrows(IOException.class, () -> CdnUpdate.download(url("/launcher.jar"), expected));
        try (var entries = Files.list(LauncherPaths.stageDir())) {
            assertEquals(0, entries.count(), "rejected download is not left behind");
        } catch (IOException e) {
            // stage dir never created — equally fine
        }
    }

    private void serve(String path, byte[] body, String sha256, String version) {
        server.createContext(
                path,
                exchange -> {
                    if (sha256 != null) {
                        exchange.getResponseHeaders().set(CdnUpdate.HASH_HEADER, sha256);
                    }
                    if (version != null) {
                        exchange.getResponseHeaders().set(CdnUpdate.VERSION_HEADER, version);
                    }
                    if (exchange.getRequestMethod().equals("HEAD")) {
                        exchange.sendResponseHeaders(200, -1);
                    } else {
                        exchange.sendResponseHeaders(200, body.length);
                        try (OutputStream out = exchange.getResponseBody()) {
                            out.write(body);
                        }
                    }
                    exchange.close();
                });
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }
}
