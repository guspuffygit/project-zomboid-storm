package io.pzstorm.launcher;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry for the {@code --steam-update} child process. Assumes cwd = game install (parent guarantees
 * it). Prints one progress line per event to stdout; the parent relays them into the launcher log.
 */
final class SteamUpdateChild {

    static final int EXIT_SOME_FAILED = 1;
    static final int EXIT_STEAM_UNAVAILABLE = 2;

    /**
     * Marks an id the parent already proved current against the published workshop metadata: it
     * gets an instant local state check instead of the per-item DownloadItem confirm, escalating to
     * the full update only when the state is not join-ready.
     */
    static final String VERIFY_PREFIX = "verify:";

    private SteamUpdateChild() {}

    static int run(String[] itemIds) {
        Path gameDir = Paths.get("").toAbsolutePath();
        try (SteamUgc steam = SteamUgc.connect(gameDir)) {
            int failures = 0;
            for (String id : itemIds) {
                boolean verifyOnly = id.startsWith(VERIFY_PREFIX);
                String rawId = verifyOnly ? id.substring(VERIFY_PREFIX.length()) : id;
                long itemId;
                try {
                    itemId = Long.parseLong(rawId.trim());
                } catch (NumberFormatException e) {
                    System.out.println("item " + rawId + " FAILED (not a workshop id)");
                    failures++;
                    continue;
                }
                try {
                    boolean ok =
                            verifyOnly
                                    ? steam.verifyItem(itemId, System.out::println)
                                    : steam.updateItem(itemId, System.out::println);
                    if (!ok) {
                        failures++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("item " + itemId + " FAILED (interrupted)");
                    return EXIT_SOME_FAILED;
                }
            }
            return failures == 0 ? 0 : EXIT_SOME_FAILED;
        } catch (SteamUgc.SteamException e) {
            System.out.println("steam unavailable: " + e.getMessage());
            return EXIT_STEAM_UNAVAILABLE;
        }
    }
}
