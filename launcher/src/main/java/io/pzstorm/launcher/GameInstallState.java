package io.pzstorm.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What Steam records about the game's own install, read from {@code
 * steamapps/appmanifest_108600.acf}.
 *
 * <p>Two things depend on it. First, the launcher starts {@code ProjectZomboid64.exe} itself, so
 * Steam's "update this game when I launch it" never fires: a player whose Steam has a pending
 * Project Zomboid patch can run the old build for weeks and only find out when a patched server
 * refuses their file checksums halfway through a join, naming a base game file no mod owns (see the
 * client's {@code StormChecksumKickNotice}). {@code StateFlags} says so before the game starts.
 *
 * <p>Second, {@code buildid} is the only cheap local identity of the installed build — the version
 * the game reports is {@code 42.20} for every hotfix — so it belongs in the content fingerprint
 * that decides whether cached join checksums still describe this install.
 */
final class GameInstallState {

    static final String APP_MANIFEST = "appmanifest_108600.acf";

    /** Valve's {@code EAppState} bits that all mean "the files on disk are not the built app". */
    private static final int UPDATE_REQUIRED = 2;

    private static final int UPDATE_QUEUED = 8;
    private static final int FILES_MISSING = 32;
    private static final int FILES_CORRUPT = 128;
    private static final int UPDATE_RUNNING = 256;
    private static final int UPDATE_PAUSED = 512;
    private static final int UPDATE_STARTED = 1024;

    private static final int NOT_CURRENT =
            UPDATE_REQUIRED
                    | UPDATE_QUEUED
                    | FILES_MISSING
                    | FILES_CORRUPT
                    | UPDATE_RUNNING
                    | UPDATE_PAUSED
                    | UPDATE_STARTED;

    final int stateFlags;
    final String buildId;

    GameInstallState(int stateFlags, String buildId) {
        this.stateFlags = stateFlags;
        this.buildId = buildId;
    }

    /** The app manifest next to the game dir, or null when there is none (non-Steam install). */
    static Path findAppManifest(LauncherConfig config) {
        for (Path steamapps : config.steamappsCandidates(config.resolveGameDir())) {
            Path manifest = steamapps.resolve(APP_MANIFEST);
            if (Files.isRegularFile(manifest)) {
                return manifest;
            }
        }
        return null;
    }

    /** Steam's record for the game, or null when it is absent or unreadable — never fatal. */
    static GameInstallState read(LauncherConfig config) {
        Path manifest = findAppManifest(config);
        if (manifest == null) {
            return null;
        }
        try {
            return parse(Files.readString(manifest, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Log.warn("Could not read " + manifest + ": " + e);
            return null;
        }
    }

    /** Pure half of {@link #read}; null when the manifest carries no {@code StateFlags}. */
    static GameInstallState parse(String manifestText) {
        String flags = topLevelValue(manifestText, "StateFlags");
        if (flags == null) {
            return null;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(flags);
        } catch (NumberFormatException e) {
            return null;
        }
        String build = topLevelValue(manifestText, "buildid");
        return new GameInstallState(parsed, build == null ? "" : build);
    }

    private static String topLevelValue(String manifestText, String key) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(key) + "\"\\s+\"([^\"]*)\"")
                        .matcher(manifestText);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** True when Steam has work outstanding on the game: the installed files are not the build. */
    boolean updatePending() {
        return (stateFlags & NOT_CURRENT) != 0;
    }

    /**
     * Logs the pending update as a warning; the join still runs. Steam's metadata is the only
     * evidence here and it can lag reality, so this never blocks a join that might well work — but
     * when the checksum kick does come, the launcher log already names the cause.
     */
    static void warnIfUpdatePending(LauncherConfig config) {
        GameInstallState state = read(config);
        if (state == null || !state.updatePending()) {
            return;
        }
        Log.warn(
                "Steam has a pending Project Zomboid update (StateFlags "
                        + state.stateFlags
                        + ", installed build "
                        + (state.buildId.isEmpty() ? "unknown" : state.buildId)
                        + "). The launcher starts the game directly, so Steam will not apply it on"
                        + " launch — let Steam finish updating Project Zomboid, or the server will"
                        + " refuse this client's file checksums mid-join.");
    }
}
