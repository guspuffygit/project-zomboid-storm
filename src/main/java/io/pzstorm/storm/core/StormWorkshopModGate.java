package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.util.StormEnv;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Decides which workshop-folder mods {@link StormModLoader} may catalog. Mods in {@code
 * ~/Zomboid/mods} and the launcher-synced directory always load; mods discovered through a workshop
 * scan (Steam workshop content and {@code ~/Zomboid/Workshop}) only load when the server has
 * enabled them:
 *
 * <ul>
 *   <li>On server JVMs the enabled set is the {@code Mods=} line of {@code
 *       <cachedir>/Server/<servername>.ini} — the same list PZ itself loads.
 *   <li>{@code -Dstorm.workshop.mods=<id;id;…>} overrides the set on any JVM.
 *   <li>A client JVM without the override loads everything, as before — at bootstrap time it has no
 *       server context (the joined server's mod list only arrives in-game, long after mod
 *       transformers must be registered).
 * </ul>
 */
public final class StormWorkshopModGate {

    /** Explicit enabled-mod-id list ({@code ;}-separated), overrides the server ini on any JVM. */
    public static final String WORKSHOP_MODS_PROPERTY = "storm.workshop.mods";

    /**
     * Set from the game arguments by {@link StormLauncher} before mods are cataloged; PZ itself
     * parses {@code -servername} / {@code -cachedir=} only after {@code GameServer.main} starts,
     * which is too late for Storm's bootstrap. System properties because launcher and mod loader
     * live in different class loaders.
     */
    public static final String SERVERNAME_PROPERTY = "storm.servername";

    public static final String CACHEDIR_PROPERTY = "storm.cachedir";

    private static final String DEFAULT_SERVER_NAME = "servertest";

    private StormWorkshopModGate() {}

    /**
     * Mirrors the {@code -servername <name>} and {@code -cachedir=<path>} parsing in {@code
     * zombie.network.GameServer.main} so the gate reads the same ini PZ will. Explicit {@code
     * -Dstorm.servername}/{@code -Dstorm.cachedir} values win over the game arguments.
     */
    public static void captureGameArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            if (arg.startsWith("-cachedir=")) {
                setIfAbsent(CACHEDIR_PROPERTY, arg.substring("-cachedir=".length()).trim());
            } else if (arg.equals("-servername") && i + 1 < args.length && args[i + 1] != null) {
                setIfAbsent(SERVERNAME_PROPERTY, args[i + 1].trim());
            }
        }
    }

    private static void setIfAbsent(String property, String value) {
        if (!value.isEmpty() && System.getProperty(property) == null) {
            System.setProperty(property, value);
        }
    }

    /**
     * The mod ids allowed to load from workshop folders, or {@code null} when workshop mods are not
     * gated on this JVM. A server whose ini is missing or has no {@code Mods=} line gets an empty
     * set — same as PZ, which would load no mods either.
     */
    public static @Nullable Set<String> enabledMods() {
        String override = System.getProperty(WORKSHOP_MODS_PROPERTY);
        if (override != null) {
            return parseModList(override);
        }
        if (!StormEnv.isStormServer()) {
            return null;
        }
        Path ini = serverIniPath();
        if (!Files.isRegularFile(ini)) {
            LOGGER.warn(
                    "Server ini not found at {} — no workshop-folder mods will be loaded."
                            + " Add mod ids to its Mods= line (or set -D{}) to enable them.",
                    ini,
                    WORKSHOP_MODS_PROPERTY);
            return Collections.emptySet();
        }
        try {
            for (String line : Files.readAllLines(ini)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Mods=")) {
                    return parseModList(trimmed.substring("Mods=".length()));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Unable to read server ini {}", ini, e);
            return Collections.emptySet();
        }
        LOGGER.warn("No Mods= line in {} — no workshop-folder mods will be loaded.", ini);
        return Collections.emptySet();
    }

    private static Path serverIniPath() {
        String cacheDir =
                System.getProperty(
                        CACHEDIR_PROPERTY,
                        Path.of(System.getProperty("user.home"), "Zomboid").toString());
        String serverName = System.getProperty(SERVERNAME_PROPERTY, DEFAULT_SERVER_NAME);
        return Path.of(cacheDir, "Server", serverName + ".ini");
    }

    /** Same tokenization PZ applies to the ini value in {@code GameServer.main}. */
    private static Set<String> parseModList(String value) {
        Set<String> mods = new LinkedHashSet<>();
        for (String modId : value.replace("\\", "").split(";")) {
            if (!modId.trim().isEmpty()) {
                mods.add(modId.trim());
            }
        }
        return mods;
    }
}
