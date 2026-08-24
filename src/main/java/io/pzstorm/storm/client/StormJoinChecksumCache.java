package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnGameStartEvent;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.jetbrains.annotations.Nullable;
import zombie.ZomboidFileSystem;
import zombie.network.GameClient;
import zombie.scripting.ScriptManager;

/**
 * Persists the client's script-checksum total between sessions, keyed on the launcher's content
 * fingerprint (see {@link StormJoinPrewarm#FINGERPRINT_PROPERTY}).
 *
 * <p>Why only the script total needs a cache: of the three totals the server compares at join time,
 * the Lua total is computed by {@code LuaManager.LoadDirBase()} and the animation total by {@code
 * AdvancedAnimator.load()} — both cheap enough to run live in the connect-time fast path. The
 * script total, however, only exists as a side effect of {@code ScriptManager.Load()}'s full script
 * parse (the single most expensive step the fast path exists to skip), and boot computes no
 * checksums at all (every {@code NetChecksum} feeder is gated on {@code GameClient.client ||
 * GameServer.server}). So the fast path replays the total this client computed on its last
 * vanilla-path join — valid exactly as long as the fingerprint (game version, mod list, workshop
 * timestamps) matches.
 *
 * <p>The cache is written after every successful multiplayer load ({@code OnGameStart} — by then
 * the server has accepted this client's checksums), and read during fast-path election. All I/O
 * failures degrade to "no cache", which just means the vanilla path runs.
 */
public final class StormJoinChecksumCache {

    private static final String FILE_NAME = "join-script-checksum.properties";
    private static final String KEY_FINGERPRINT = "fingerprint";
    private static final String KEY_SCRIPT_CHECKSUM = "scriptChecksum";

    private StormJoinChecksumCache() {}

    /** The cached script total, or null when absent or recorded under a different fingerprint. */
    public static @Nullable String readScriptChecksum(String fingerprint) {
        Path file = cacheFile();
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties cached = new Properties();
            cached.load(reader);
            if (!fingerprint.equals(cached.getProperty(KEY_FINGERPRINT))) {
                return null;
            }
            String checksum = cached.getProperty(KEY_SCRIPT_CHECKSUM, "");
            return checksum.isEmpty() ? null : checksum;
        } catch (IOException e) {
            LOGGER.warn("Could not read join checksum cache {}: {}", file, e.toString());
            return null;
        }
    }

    /**
     * After a completed multiplayer load the server has accepted this client's checksums, so the
     * script total {@code ScriptManager} computed for the current content is proven good — record
     * it under the launcher's fingerprint for the next join's fast path. No-op outside a
     * launcher-started MP client.
     */
    @SubscribeEvent
    public static void onGameStart(OnGameStartEvent event) {
        try {
            if (!GameClient.client) {
                return;
            }
            String fingerprint = StormJoinPrewarm.fingerprint();
            if (fingerprint == null) {
                return;
            }
            String checksum = ScriptManager.instance.getChecksum();
            if (checksum == null || checksum.isEmpty()) {
                return;
            }
            write(fingerprint, checksum);
        } catch (Throwable t) {
            // cache-only bookkeeping; never let it near the actual game start
            LOGGER.warn("Could not record join checksum cache: {}", t.toString());
        }
    }

    static void write(String fingerprint, String scriptChecksum) throws IOException {
        Path file = cacheFile();
        if (file == null) {
            return;
        }
        Properties cached = new Properties();
        cached.setProperty(KEY_FINGERPRINT, fingerprint);
        cached.setProperty(KEY_SCRIPT_CHECKSUM, scriptChecksum);
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            cached.store(writer, "Storm join fast-path cache; safe to delete");
        }
        LOGGER.info("Join checksum cache recorded for fingerprint {}", fingerprint);
    }

    private static @Nullable Path cacheFile() {
        try {
            String cacheDir = ZomboidFileSystem.instance.getCacheDir();
            if (cacheDir == null || cacheDir.isEmpty()) {
                return null;
            }
            return Paths.get(cacheDir, "storm", FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }
}
