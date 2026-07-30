package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModSyncTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> remoteFiles = new LinkedHashMap<>();
    private final List<String> remoteDirs = new ArrayList<>();

    @TempDir Path targetDir;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                ModManifest.MANIFEST_PATH,
                exchange -> {
                    byte[] body = manifestJson().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.createContext(
                ModManifest.FILE_PATH,
                exchange -> {
                    String query = exchange.getRequestURI().getRawQuery();
                    String path =
                            URLDecoder.decode(
                                    query.substring("path=".length()), StandardCharsets.UTF_8);
                    byte[] body = remoteFiles.get(path);
                    if (body == null) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String manifestJson() {
        List<Object> files = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : remoteFiles.entrySet()) {
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("path", entry.getKey());
            file.put("sha256", Sha256.of(entry.getValue()));
            file.put("size", (long) entry.getValue().length);
            files.add(file);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("stormVersion", "42.20.0_2.4.0-test");
        root.put("dirs", new ArrayList<Object>(remoteDirs));
        root.put("files", files);
        return Json.write(root);
    }

    @Test
    void syncsUpdatesAndDeletes() throws IOException {
        remoteDirs.add("my-mod/common");
        remoteDirs.add("my-mod/42");
        remoteFiles.put("my-mod/mod.info", "id=my-mod\nname=My Mod\n".getBytes());
        remoteFiles.put("my-mod/42/my-mod.jar", "JARBYTES-1".getBytes());

        ModSync sync = new ModSync();
        ModManifest manifest = sync.fetchManifest(base);
        assertEquals("42.20.0_2.4.0-test", manifest.stormVersion);

        ModSync.SyncResult first = sync.sync(base, manifest, targetDir);
        assertEquals(2, first.downloaded);
        assertEquals(0, first.kept);
        assertTrue(
                Files.isDirectory(targetDir.resolve("my-mod/common")),
                "empty common/ dir must be materialized");
        assertEquals(
                "JARBYTES-1",
                new String(Files.readAllBytes(targetDir.resolve("my-mod/42/my-mod.jar"))));

        // second sync: everything up to date
        ModSync.SyncResult second = sync.sync(base, sync.fetchManifest(base), targetDir);
        assertEquals(0, second.downloaded);
        assertEquals(2, second.kept);

        // server updates the jar and drops mod.info; a stale local file appears
        remoteFiles.put("my-mod/42/my-mod.jar", "JARBYTES-2-LONGER".getBytes());
        remoteFiles.remove("my-mod/mod.info");
        Files.write(targetDir.resolve("stale.jar"), "old".getBytes());

        ModSync.SyncResult third = sync.sync(base, sync.fetchManifest(base), targetDir);
        assertEquals(1, third.downloaded);
        assertEquals(2, third.deleted, "mod.info and stale.jar should be removed");
        assertEquals(
                "JARBYTES-2-LONGER",
                new String(Files.readAllBytes(targetDir.resolve("my-mod/42/my-mod.jar"))));
        assertFalse(Files.exists(targetDir.resolve("my-mod/mod.info")));
        assertFalse(Files.exists(targetDir.resolve("stale.jar")));
    }

    @Test
    void rejectsChecksumMismatch() {
        remoteFiles.put("m/x.jar", "payload".getBytes());
        ModSync sync = new ModSync();
        ModManifest manifest = sync.fetchManifest(base);
        remoteFiles.put("m/x.jar", "tampered-after-manifest".getBytes());
        assertThrows(ModSync.SyncException.class, () -> sync.sync(base, manifest, targetDir));
        assertFalse(
                Files.exists(targetDir.resolve("m/x.jar")), "tampered file must not be installed");
    }

    @Test
    void rejectsTraversalManifest() {
        ModSync sync = new ModSync();
        assertThrows(
                RuntimeException.class,
                () ->
                        ModManifest.parse(
                                "{\"files\": [{\"path\": \"../evil.jar\", \"sha256\": \""
                                        + "a".repeat(64)
                                        + "\", \"size\": 1}]}"));
        // and even if a hostile manifest object were constructed, sync re-validates:
        ModManifest hostile =
                new ModManifest(
                        "x",
                        List.of(),
                        List.of(new ModManifest.Entry("ok/../../evil.jar", "a".repeat(64), 1)));
        assertThrows(RuntimeException.class, () -> sync.sync(base, hostile, targetDir));
    }
}
