package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentSizeCheckTest {

    @TempDir Path tmp;

    private Path itemDir(String id, int... fileSizes) throws IOException {
        Path dir = tmp.resolve(id).resolve("mods").resolve("A");
        Files.createDirectories(dir);
        for (int i = 0; i < fileSizes.length; i++) {
            Files.write(dir.resolve("f" + i + ".lua"), new byte[fileSizes[i]]);
        }
        return dir;
    }

    @Test
    void byteTotalMatchingTheRecordVerifiesAndCaches() throws IOException {
        itemDir("100", 10, 20);
        Properties cache = new Properties();

        List<String> mismatched =
                ContentSizeCheck.check(
                        List.of("100"), Map.of("100", 30L), Map.of("100", 7L), tmp, cache);

        assertTrue(mismatched.isEmpty());
        assertEquals("7:30", cache.getProperty("100"));
    }

    @Test
    void staleBytesUnderACurrentStampAreCaught() throws IOException {
        // the field case: install stamp says current, disk still holds the previous release
        itemDir("100", 10);

        List<String> mismatched =
                ContentSizeCheck.check(
                        List.of("100"),
                        Map.of("100", 30L),
                        Map.of("100", 7L),
                        tmp,
                        new Properties());

        assertEquals(List.of("100"), mismatched);
    }

    @Test
    void missingContentDirWithANonZeroRecordIsAMismatch() {
        List<String> mismatched =
                ContentSizeCheck.check(
                        List.of("100"),
                        Map.of("100", 30L),
                        Map.of("100", 7L),
                        tmp,
                        new Properties());

        assertEquals(List.of("100"), mismatched);
    }

    @Test
    void cachedFingerprintSkipsTheWalkAndAMismatchEvictsIt() throws IOException {
        itemDir("100", 10);
        Properties cache = new Properties();
        cache.setProperty("100", "7:30");

        assertTrue(
                ContentSizeCheck.check(
                                List.of("100"), Map.of("100", 30L), Map.of("100", 7L), tmp, cache)
                        .isEmpty());

        // Steam commits new metadata -> fingerprint changes -> the stale bytes surface
        List<String> mismatched =
                ContentSizeCheck.check(
                        List.of("100"), Map.of("100", 44L), Map.of("100", 8L), tmp, cache);
        assertEquals(List.of("100"), mismatched);
        assertNull(cache.getProperty("100"));
    }

    @Test
    void itemsWithoutAnInstallRecordProveNothing() {
        assertTrue(
                ContentSizeCheck.check(List.of("100"), Map.of(), Map.of(), tmp, new Properties())
                        .isEmpty());
    }

    @Test
    void directoryBytesSumsRegularFilesOnly() throws IOException {
        itemDir("100", 5, 7);

        assertEquals(12, ContentSizeCheck.directoryBytes(tmp.resolve("100")));
        assertEquals(0, ContentSizeCheck.directoryBytes(tmp.resolve("nope")));
    }
}
