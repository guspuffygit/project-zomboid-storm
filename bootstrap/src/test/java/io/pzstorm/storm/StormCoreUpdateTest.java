package io.pzstorm.storm;

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

class StormCoreUpdateTest {

    private static final String ITEM_JAR_NAME = "storm-42.20.2_2.5.1.jar";

    @TempDir Path tmp;

    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        System.setProperty(StormCoreUpdate.ZOMBOID_DIR_PROPERTY, tmp.resolve("Zomboid").toString());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        System.clearProperty(StormCoreUpdate.ZOMBOID_DIR_PROPERTY);
        System.clearProperty(StormCoreUpdate.URL_PROPERTY);
    }

    @Test
    void parseReadsVersionsFromJarName() {
        StormCoreUpdate.LocalJar release = StormCoreUpdate.parse("storm-42.20.2_2.5.1.jar");
        assertEquals("42.20.2", release.pzVersion());
        assertEquals("2.5.1", release.stormVersion());
        assertFalse(release.snapshot());

        StormCoreUpdate.LocalJar snapshot = StormCoreUpdate.parse("storm-42.20.2_2.5.1-SNAPSHOT.jar");
        assertEquals("2.5.1", snapshot.stormVersion());
        assertTrue(snapshot.snapshot());

        assertNull(StormCoreUpdate.parse("byte-buddy-1.18.4.jar"));
        assertNull(StormCoreUpdate.parse("storm-bootstrap.jar"));
        assertNull(StormCoreUpdate.parse("storm-launcher.jar"));
    }

    @Test
    void isNewerComparesDottedNumerics() {
        assertTrue(StormCoreUpdate.isNewer("2.5.2", "2.5.1"));
        assertTrue(StormCoreUpdate.isNewer("2.10.0", "2.9.9"), "numeric, not lexicographic");
        assertTrue(StormCoreUpdate.isNewer("3.0", "2.9.9"), "missing segments are zero");
        assertFalse(StormCoreUpdate.isNewer("2.5.1", "2.5.1"));
        assertFalse(StormCoreUpdate.isNewer("2.5.0", "2.5"), "trailing zero segment is equal");
        assertFalse(StormCoreUpdate.isNewer("2.4.9", "2.5.0"));
        assertFalse(StormCoreUpdate.isNewer("2.5.2", "2.5.1-SNAPSHOT"), "snapshot fails soft");
        assertFalse(StormCoreUpdate.isNewer("dev", "2.5.1"));
        assertFalse(StormCoreUpdate.isNewer("2.5.2", null));
        assertFalse(StormCoreUpdate.isNewer(null, "2.5.1"));
    }

    @Test
    void updateUrlDefaultsToPzVersionedKeyAndHonorsOverride() {
        assertEquals(
                "https://guspuffy.com/storm/core/42.20.2/storm.jar",
                StormCoreUpdate.updateUrl("42.20.2"));

        System.setProperty(StormCoreUpdate.URL_PROPERTY, "http://example.test/storm.jar");
        assertEquals("http://example.test/storm.jar", StormCoreUpdate.updateUrl("42.20.2"));

        System.setProperty(StormCoreUpdate.URL_PROPERTY, "");
        assertNull(StormCoreUpdate.updateUrl("42.20.2"), "empty override disables the check");
    }

    @Test
    void fetchReadsMetadataHeaders() {
        byte[] jar = "storm v2.5.2".getBytes();
        String sha = Sha256.of(jar);
        serve("/storm.jar", jar, sha, "2.5.2");

        StormCoreUpdate.Remote remote = StormCoreUpdate.fetch(url("/storm.jar"));

        assertEquals("2.5.2", remote.version());
        assertEquals(sha, remote.sha256());
    }

    @Test
    void fetchIsNullOnMissingObjectOrMissingMetadata() {
        serve("/no-meta.jar", "bytes".getBytes(), null, null);
        assertNull(StormCoreUpdate.fetch(url("/absent.jar")), "404");
        assertNull(StormCoreUpdate.fetch(url("/no-meta.jar")), "no metadata headers");
        assertNull(StormCoreUpdate.fetch("http://127.0.0.1:1/storm.jar"), "unreachable host");
    }

    @Test
    void resolveStagesAndReturnsNewerCdnJar() throws Exception {
        Path itemJar = itemJar("old build");
        byte[] published = "new build".getBytes();
        String sha = Sha256.of(published);
        serve("/storm.jar", published, sha, "2.5.2");
        System.setProperty(StormCoreUpdate.URL_PROPERTY, url("/storm.jar"));

        Path resolved = StormCoreUpdate.resolve(itemJar);

        assertEquals(StormCoreUpdate.stagedPath(sha), resolved);
        assertEquals(sha, Sha256.of(resolved));
        try (var entries = Files.list(StormCoreUpdate.stageDir())) {
            assertEquals(1, entries.count(), "no tmp download leftovers");
        }
    }

    @Test
    void resolveReusesExistingStagedJarWithoutDownloading() throws Exception {
        Path itemJar = itemJar("old build");
        byte[] published = "new build".getBytes();
        String sha = Sha256.of(published);
        Path staged = StormCoreUpdate.stagedPath(sha);
        Files.createDirectories(staged.getParent());
        Files.write(staged, published);
        // HEAD-only server: a GET (download attempt) would return no body and fail the hash check
        server.createContext(
                "/storm.jar",
                exchange -> {
                    exchange.getResponseHeaders().set(StormCoreUpdate.HASH_HEADER, sha);
                    exchange.getResponseHeaders().set(StormCoreUpdate.VERSION_HEADER, "2.5.2");
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        System.setProperty(StormCoreUpdate.URL_PROPERTY, url("/storm.jar"));

        assertEquals(staged, StormCoreUpdate.resolve(itemJar));
    }

    @Test
    void resolveKeepsItemJarWhenCdnIsNotNewer() throws Exception {
        Path itemJar = itemJar("current build");
        serve("/storm.jar", "stale".getBytes(), Sha256.of("stale".getBytes()), "2.5.1");
        System.setProperty(StormCoreUpdate.URL_PROPERTY, url("/storm.jar"));

        assertEquals(itemJar, StormCoreUpdate.resolve(itemJar));
    }

    @Test
    void resolveKeepsItemJarWhenHashesMatchDespiteHigherVersionLabel() throws Exception {
        byte[] bytes = "same build".getBytes();
        Path itemJar = itemJar("same build");
        serve("/storm.jar", bytes, Sha256.of(bytes), "2.5.2");
        System.setProperty(StormCoreUpdate.URL_PROPERTY, url("/storm.jar"));

        assertEquals(itemJar, StormCoreUpdate.resolve(itemJar));
    }

    @Test
    void resolveNeverUpdatesSnapshotBuilds() throws Exception {
        Path itemJar = tmp.resolve("storm-42.20.2_2.5.1-SNAPSHOT.jar");
        Files.write(itemJar, "dev build".getBytes());
        byte[] published = "cdn build".getBytes();
        serve("/storm.jar", published, Sha256.of(published), "2.5.2");
        System.setProperty(StormCoreUpdate.URL_PROPERTY, url("/storm.jar"));

        assertEquals(itemJar, StormCoreUpdate.resolve(itemJar));
    }

    @Test
    void resolveFailsSoftWhenOffline() throws Exception {
        Path itemJar = itemJar("current build");
        System.setProperty(StormCoreUpdate.URL_PROPERTY, "http://127.0.0.1:1/storm.jar");

        assertEquals(itemJar, StormCoreUpdate.resolve(itemJar));
    }

    @Test
    void resolveKeepsItemJarOnUnparseableName() throws Exception {
        Path itemJar = tmp.resolve("storm-core.jar");
        Files.write(itemJar, "bytes".getBytes());

        assertEquals(itemJar, StormCoreUpdate.resolve(itemJar));
    }

    @Test
    void downloadRefusesHashMismatch() {
        byte[] jar = "tampered".getBytes();
        serve("/storm.jar", jar, null, null);
        String expected = Sha256.of("what was published".getBytes());

        assertThrows(
                IOException.class, () -> StormCoreUpdate.download(url("/storm.jar"), expected));
        try (var entries = Files.list(StormCoreUpdate.stageDir())) {
            assertEquals(0, entries.count(), "rejected download is not left behind");
        } catch (IOException e) {
            // stage dir never created — equally fine
        }
    }

    @Test
    void resolveSweepsStaleStages() throws Exception {
        Path itemJar = itemJar("old build");
        Path stale = StormCoreUpdate.stageDir().resolve("deadbeefdeadbeef").resolve("storm.jar");
        Files.createDirectories(stale.getParent());
        Files.write(stale, "obsolete".getBytes());
        byte[] published = "new build".getBytes();
        String sha = Sha256.of(published);
        serve("/storm.jar", published, sha, "2.5.2");
        System.setProperty(StormCoreUpdate.URL_PROPERTY, url("/storm.jar"));

        Path resolved = StormCoreUpdate.resolve(itemJar);

        assertTrue(Files.isRegularFile(resolved));
        assertFalse(Files.exists(stale.getParent()), "stale stage swept");
    }

    private Path itemJar(String content) throws IOException {
        Path jar = tmp.resolve(ITEM_JAR_NAME);
        Files.write(jar, content.getBytes());
        return jar;
    }

    private void serve(String path, byte[] body, String sha256, String version) {
        server.createContext(
                path,
                exchange -> {
                    if (sha256 != null) {
                        exchange.getResponseHeaders().set(StormCoreUpdate.HASH_HEADER, sha256);
                    }
                    if (version != null) {
                        exchange.getResponseHeaders().set(StormCoreUpdate.VERSION_HEADER, version);
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
