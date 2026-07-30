package io.pzstorm.storm.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientModsEndpointsTest {

    @TempDir Path tmp;

    @Test
    @SuppressWarnings("unchecked")
    void buildsManifestFromDirectoryTree() throws IOException {
        Path mod = tmp.resolve("my-mod");
        Files.createDirectories(mod.resolve("common"));
        Files.createDirectories(mod.resolve("42"));
        Files.write(mod.resolve("mod.info"), "id=my-mod\nname=My Mod\n".getBytes());
        Files.write(mod.resolve("42").resolve("my-mod.jar"), "JARBYTES".getBytes());

        Map<String, Object> manifest = ClientModsEndpoints.buildManifest(tmp);

        List<String> dirs = (List<String>) manifest.get("dirs");
        assertTrue(dirs.contains("my-mod/common"), dirs.toString());
        assertTrue(dirs.contains("my-mod/42"));

        List<Map<String, Object>> files = (List<Map<String, Object>>) manifest.get("files");
        assertEquals(2, files.size());
        Map<String, Object> jar =
                files.stream()
                        .filter(f -> f.get("path").equals("my-mod/42/my-mod.jar"))
                        .findFirst()
                        .orElseThrow();
        assertEquals((long) "JARBYTES".length(), jar.get("size"));
        assertEquals(64, ((String) jar.get("sha256")).length());

        // hash cache invalidates when content changes
        String before = (String) jar.get("sha256");
        Files.write(mod.resolve("42").resolve("my-mod.jar"), "DIFFERENT-CONTENT".getBytes());
        Map<String, Object> updated = ClientModsEndpoints.buildManifest(tmp);
        String after =
                (String)
                        ((List<Map<String, Object>>) updated.get("files"))
                                .stream()
                                        .filter(f -> f.get("path").equals("my-mod/42/my-mod.jar"))
                                        .findFirst()
                                        .orElseThrow()
                                        .get("sha256");
        assertNotEquals(before, after);
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsUnpublishableNamesAndMissingRoot() throws IOException {
        Files.createDirectories(tmp.resolve("ok-mod"));
        Files.write(tmp.resolve("ok-mod").resolve("good.jar"), "x".getBytes());
        Files.write(tmp.resolve("ok-mod").resolve(".hidden.part"), "y".getBytes());

        Map<String, Object> manifest = ClientModsEndpoints.buildManifest(tmp);
        List<Map<String, Object>> files = (List<Map<String, Object>>) manifest.get("files");
        assertEquals(1, files.size());
        assertEquals("ok-mod/good.jar", files.get(0).get("path"));

        Map<String, Object> empty = ClientModsEndpoints.buildManifest(tmp.resolve("missing"));
        assertTrue(((List<?>) empty.get("files")).isEmpty());
        assertTrue(((List<?>) empty.get("dirs")).isEmpty());
    }

    @Test
    void validatesRelativePaths() {
        assertTrue(ClientModsEndpoints.isValidRelativePath("my-mod/42/x.jar"));
        assertTrue(ClientModsEndpoints.isValidRelativePath("My Mod 2/42.1/lib-1.2+b4.jar"));
        String[] evil = {
            null,
            "",
            "/abs",
            "a//b",
            "a/",
            "../up",
            "a/../b",
            "a\\b",
            "a/./b",
            ".h/x",
            "a/b ",
            "con/x.jar",
            "nul.txt/y",
            "a/b/c/d/e/f/g/h/i"
        };
        for (String path : evil) {
            assertFalse(ClientModsEndpoints.isValidRelativePath(path), "should reject: " + path);
        }
    }
}
