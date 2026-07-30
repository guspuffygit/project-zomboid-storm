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

    private SteamUpdateChild() {}

    static int run(String[] itemIds) {
        Path gameDir = Paths.get("").toAbsolutePath();
        try (SteamUgc steam = SteamUgc.connect(gameDir)) {
            int failures = 0;
            for (String id : itemIds) {
                long itemId;
                try {
                    itemId = Long.parseLong(id.trim());
                } catch (NumberFormatException e) {
                    System.out.println("item " + id + " FAILED (not a workshop id)");
                    failures++;
                    continue;
                }
                try {
                    if (!steam.updateItem(itemId, System.out::println)) {
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
