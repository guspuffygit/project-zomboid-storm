package io.pzstorm.launcher;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Finds workshop items whose on-disk content no longer adds up to the byte total Steam recorded at
 * install time ({@code WorkshopItemsInstalled.<id>.size} in appworkshop_108600.acf). Steam has been
 * observed bumping an item's install {@code timeupdated} without ever writing the new release's
 * bytes; every timestamp gate — {@link WorkshopStaleScan}, the game's own WorkshopConfirm — then
 * calls the install current while the content is a whole release behind, and the join dies at the
 * server's file checksum with no recovery path. The byte total can't be fooled the same way: Steam
 * only records it after committing the files it describes.
 *
 * <p>Walking every item's tree costs file-metadata I/O on each join, so verified results are cached
 * per {@code (timeupdated, size)} pair in {@code size-check.properties}; an item is re-walked only
 * after Steam commits new install metadata. The cache means damage done to content <em>after</em> a
 * verification (manual deletion, disk corruption) is not caught here — the checksum-kick handoff
 * ({@link JoinFailureHandoff}) catches that one failed join later.
 */
final class ContentSizeCheck {

    static final String CACHE_FILE_NAME = "size-check.properties";

    private ContentSizeCheck() {}

    /**
     * Item ids among {@code itemIds} whose content directory's byte total contradicts the acf's
     * install record. Items with no install record prove nothing (first installs are the update
     * path's job), and a directory that cannot be fully walked proves nothing either.
     */
    static List<String> mismatched(LauncherConfig config, Collection<String> itemIds)
            throws IOException {
        Path acf = WorkshopStaleScan.findAppWorkshopAcf(config);
        if (acf == null) {
            return List.of();
        }
        String acfText = Files.readString(acf, StandardCharsets.UTF_8);
        Properties cache = loadCache(cacheFile());
        List<String> mismatched =
                check(
                        itemIds,
                        WorkshopStaleScan.parseInstalledSizes(acfText),
                        WorkshopStaleScan.parseInstalledTimestamps(acfText),
                        acf.getParent().resolve("content").resolve("108600"),
                        cache);
        storeCache(cacheFile(), cache);
        return mismatched;
    }

    /** Pure half of {@link #mismatched}; mutates {@code cache} with newly verified items. */
    static List<String> check(
            Collection<String> itemIds,
            Map<String, Long> recordedSizes,
            Map<String, Long> recordedStamps,
            Path contentRoot,
            Properties cache) {
        List<String> mismatched = new ArrayList<>();
        for (String id : new LinkedHashSet<>(itemIds)) {
            Long recorded = recordedSizes.get(id);
            if (recorded == null) {
                continue;
            }
            String fingerprint = recordedStamps.getOrDefault(id, 0L) + ":" + recorded;
            if (fingerprint.equals(cache.getProperty(id))) {
                continue;
            }
            long actual = directoryBytes(contentRoot.resolve(id));
            if (actual < 0) {
                continue;
            }
            if (actual == recorded) {
                cache.setProperty(id, fingerprint);
            } else {
                cache.remove(id);
                mismatched.add(id);
                Log.warn(
                        "Workshop item "
                                + id
                                + " has "
                                + actual
                                + " bytes on disk but Steam recorded "
                                + recorded
                                + " at install — the content is not what the install stamp"
                                + " claims.");
            }
        }
        return mismatched;
    }

    /**
     * Sum of regular-file sizes under {@code dir}; a missing directory is 0 bytes (a real mismatch
     * against any non-zero record), and a directory that can't be fully walked returns -1 so the
     * caller concludes nothing rather than repairing on a guess.
     */
    static long directoryBytes(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> tree = Files.walk(dir)) {
            long total = 0;
            for (Path file : (Iterable<Path>) tree::iterator) {
                if (Files.isRegularFile(file)) {
                    total += Files.size(file);
                }
            }
            return total;
        } catch (IOException | RuntimeException e) {
            Log.warn("Could not measure " + dir + ": " + e.getMessage());
            return -1;
        }
    }

    static Path cacheFile() {
        return LauncherPaths.launcherDir().resolve(CACHE_FILE_NAME);
    }

    private static Properties loadCache(Path file) {
        Properties cache = new Properties();
        if (!Files.isRegularFile(file)) {
            return cache;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            cache.load(reader);
        } catch (IOException e) {
            Log.warn("Could not read " + file + ": " + e.getMessage());
        }
        return cache;
    }

    private static void storeCache(Path file, Properties cache) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                cache.store(writer, "Workshop items verified byte-complete; safe to delete");
            }
        } catch (IOException e) {
            Log.warn("Could not write " + file + ": " + e.getMessage());
        }
    }
}
