package io.pzstorm.launcher;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The record the Storm client writes when the server's file checksum kicks it mid-join ({@code
 * io.pzstorm.storm.client.StormChecksumKickNotice} — file name and keys are mirrored there, the
 * launcher has no Storm classes on its classpath). It names the exact file the server rejected;
 * mapping that to the workshop item that owns it tells the next join which item's bytes are proven
 * wrong regardless of what Steam's install metadata claims — the case where a plain DownloadItem
 * already answered "no work needed" once and only the full repair (delete + subscription cycle)
 * helps.
 */
final class JoinFailureHandoff {

    static final String FILE_NAME = "last-join-failure.properties";

    /** A record this old outlived its usefulness — every join since had a chance to act on it. */
    static final long MAX_AGE_MS = TimeUnit.DAYS.toMillis(14);

    private static final Pattern WORKSHOP_ITEM =
            Pattern.compile("[/\\\\]content[/\\\\]108600[/\\\\](\\d+)[/\\\\]");

    final long timestampMs;
    final String reason;
    final String relPath;
    final String absPath;
    final String server;

    JoinFailureHandoff(
            long timestampMs, String reason, String relPath, String absPath, String server) {
        this.timestampMs = timestampMs;
        this.reason = reason;
        this.relPath = relPath;
        this.absPath = absPath;
        this.server = server;
    }

    static Path file() {
        return LauncherPaths.launcherDir().resolve(FILE_NAME);
    }

    /** The recorded failure, or null when absent or unreadable (unreadable is deleted). */
    static JoinFailureHandoff read() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            Log.warn("Could not read " + file + ": " + e.getMessage());
            delete();
            return null;
        }
        long timestampMs;
        try {
            timestampMs = Long.parseLong(props.getProperty("timestampMs", ""));
        } catch (NumberFormatException e) {
            timestampMs = 0;
        }
        return new JoinFailureHandoff(
                timestampMs,
                props.getProperty("reason", ""),
                props.getProperty("relPath", ""),
                props.getProperty("absPath", ""),
                props.getProperty("server", ""));
    }

    static void delete() {
        try {
            Files.deleteIfExists(file());
        } catch (IOException e) {
            Log.warn("Could not delete " + file() + ": " + e.getMessage());
        }
    }

    /**
     * The workshop item owning the rejected file, from its absolute path ({@code
     * …/steamapps/workshop/content/108600/<id>/…}); null when the file lives outside workshop
     * content (game media, a manually installed mod) — Steam can't repair those.
     */
    String workshopItemId() {
        Matcher matcher = WORKSHOP_ITEM.matcher(absPath);
        return matcher.find() ? matcher.group(1) : null;
    }

    boolean expired(long nowMs) {
        return nowMs - timestampMs > MAX_AGE_MS;
    }
}
