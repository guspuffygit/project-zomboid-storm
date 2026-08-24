package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Promotes the Storm Launcher's join handoff file into system properties, very early in {@link
 * StormLauncher#main}. The launcher's per-join data — the server's mod list for {@link
 * StormWorkshopModGate}, plus the join prewarm properties ({@code
 * io.pzstorm.storm.client.StormJoinPrewarm}) — scales with the server and ProjectZomboid64.exe
 * silently dies when any single command-line argument exceeds ~1 KB, so the launcher writes it all
 * to a properties file and passes only {@value #JOIN_FILE_PROPERTY}. After promotion every consumer
 * reads plain system properties, exactly as if the values had been {@code -D} arguments.
 *
 * <p>An explicit {@code -D} for the same key wins over the file, matching HotSpot's later-arg-wins
 * behaviour the file replaces. Every failure is soft: the launch proceeds without the promoted
 * properties, which only costs the mod gate and the join fast path.
 */
public final class StormJoinHandoff {

    /** Path to the handoff file. Keep in sync with the launcher's {@code GameLaunch}. */
    public static final String JOIN_FILE_PROPERTY = "storm.join.file";

    private StormJoinHandoff() {}

    public static void apply() {
        apply(System.getProperty(JOIN_FILE_PROPERTY));
    }

    static void apply(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Properties handoff = new Properties();
            try (Reader reader = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8)) {
                handoff.load(reader);
            }
            int applied = 0;
            for (String key : handoff.stringPropertyNames()) {
                // only storm.* may be defined from a file, and never over an explicit -D
                if (!key.startsWith("storm.") || System.getProperty(key) != null) {
                    continue;
                }
                System.setProperty(key, handoff.getProperty(key));
                applied++;
            }
            LOGGER.info(
                    "Applied {} propert{} from join handoff file {}",
                    applied,
                    applied == 1 ? "y" : "ies",
                    path);
        } catch (Throwable t) {
            LOGGER.error(
                    "Failed to read join handoff file "
                            + path
                            + " — workshop mod gate and join prewarm stay unarmed",
                    t);
        }
    }
}
