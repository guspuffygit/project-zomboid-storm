package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnRenderTickEvent;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;
import org.jetbrains.annotations.Nullable;
import zombie.GameWindow;
import zombie.ZomboidFileSystem;
import zombie.network.GameClient;
import zombie.network.NetChecksum;

/**
 * Turns the server's file-checksum kick from a dead end into the start of a repair. When {@code
 * NetChecksum.comparer} fails, vanilla shows only the mismatch ("File doesn't match the one on the
 * server" plus paths) with no way forward — and the stale bytes that caused it also satisfy every
 * timestamp gate on the next join, so without help the same kick repeats forever. This handler (a)
 * records the rejected file in {@code <Zomboid>/storm/launcher/last-join-failure.properties}, which
 * the Storm Launcher's next join consumes to force a clean re-download of the owning workshop item
 * (see the launcher's {@code JoinFailureHandoff} — file name and keys are mirrored there), and (b)
 * appends a notice below the vanilla error telling the player exactly what happens next.
 *
 * <p>The advice splits on who owns the rejected file. Under the game's own {@code media} folder no
 * mod is involved and no workshop re-download can help: the two installs of Project Zomboid are on
 * different builds, which is what every game patch does to players whose Steam has not applied the
 * update yet (the launcher starts the exe directly, so Steam's update-on-launch never fires) and to
 * every player of a server that has not been updated yet.
 *
 * <p>No new bytecode patch: it rides the render-tick bridge event. Both screens that show the kick
 * ({@code GameLoadingState.render} and {@code ServerDisconnectState.render}) re-read {@code
 * GameWindow.kickReason} every frame and the game's font draws {@code \n} as line breaks, so
 * re-appending each tick both renders below the error and survives any late overwrite by {@code
 * Comparer.update()}. Every failure degrades to the plain vanilla screen.
 */
public final class StormChecksumKickNotice {

    /** Mirrored by the launcher's {@code JoinFailureHandoff}. */
    static final String HANDOFF_FILE_NAME = "last-join-failure.properties";

    /** The per-file reason strings {@code ChecksumPacket.getReason} can put in front of a path. */
    private static final String[] FILE_REASONS = {
        "File doesn't match the one on the server",
        "File doesn't exist on the server",
        "File doesn't exist on the client",
        "File status unknown",
    };

    /** First notice line; doubles as the already-appended marker. */
    private static final String NOTICE_HEAD =
            "One of your installed mods is out of date or damaged on this computer.";

    /** Same role as {@link #NOTICE_HEAD} for a file the base game owns. */
    private static final String NOTICE_GAME_HEAD =
            "This file belongs to Project Zomboid itself - no mod owns it.";

    private static final String NOTICE_RECORDED =
            "\n\n"
                    + NOTICE_HEAD
                    + "\nStorm has recorded which one. Quit to desktop, then press Join in the"
                    + " Storm Launcher -\nit will re-download that mod and repair this"
                    + " automatically.";

    private static final String NOTICE_UNRECORDED =
            "\n\n"
                    + NOTICE_HEAD
                    + "\nQuit to desktop, then press Join in the Storm Launcher. If it happens"
                    + " again,\nunsubscribe and resubscribe the mod in the Steam Workshop.";

    private static final String NOTICE_GAME =
            "\n\n"
                    + NOTICE_GAME_HEAD
                    + "\nYour game build and the server's do not match. Quit to desktop, open"
                    + " Steam and\nlet it finish updating Project Zomboid - the launcher starts"
                    + " the game directly,\nso Steam never updates it on launch. If Steam says the"
                    + " game is up to date,\nthe server is running an older build: tell the"
                    + " server admin.";

    private static boolean handled;
    private static boolean recorded;
    private static boolean gameFile;
    private static boolean warnedFailure;

    private StormChecksumKickNotice() {}

    @SubscribeEvent
    public static void onRenderTick(OnRenderTickEvent event) {
        try {
            if (NetChecksum.comparer.state != NetChecksum.Comparer.State.Failed) {
                handled = false;
                return;
            }
            Parsed parsed = parse(NetChecksum.comparer.error);
            if (parsed == null) {
                return;
            }
            if (!handled) {
                handled = true;
                gameFile = insideGameMedia(parsed.absPath, mediaRoot());
                recorded = writeHandoff(parsed);
                LOGGER.error(
                        "Kicked by the server's file checksum: {} ({}, {})",
                        parsed.relPath,
                        parsed.reason,
                        gameFile ? "base game file - game build mismatch" : "mod file");
            }
            String reason = GameWindow.kickReason;
            if (reason != null && reason.startsWith(parsed.reason)) {
                GameWindow.kickReason = withNotice(reason, recorded, gameFile);
            }
        } catch (Throwable t) {
            if (!warnedFailure) {
                warnedFailure = true;
                LOGGER.warn("Checksum kick notice failed", t);
            }
        }
    }

    /** {@code reason} with the notice appended, unchanged when it already carries it. */
    static String withNotice(String reason, boolean recorded, boolean gameFile) {
        if (reason.contains(NOTICE_HEAD) || reason.contains(NOTICE_GAME_HEAD)) {
            return reason;
        }
        if (gameFile) {
            return reason + NOTICE_GAME;
        }
        return reason + (recorded ? NOTICE_RECORDED : NOTICE_UNRECORDED);
    }

    /**
     * True when the rejected file sits under the game's own {@code media} folder, which no mod can
     * write to: the mismatch is then between the two installs of Project Zomboid, not between mod
     * copies, and no re-download of workshop content can fix it. A blank {@code mediaRoot} (the
     * file system is not initialised) reads as "not the game's", keeping the mod advice.
     */
    static boolean insideGameMedia(String absPath, @Nullable String mediaRoot) {
        if (absPath == null || absPath.isEmpty() || mediaRoot == null || mediaRoot.isEmpty()) {
            return false;
        }
        String path = normalize(absPath);
        String root = normalize(mediaRoot);
        return path.startsWith(root.endsWith("/") ? root : root + "/");
    }

    private static String normalize(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ENGLISH);
    }

    private static @Nullable String mediaRoot() {
        try {
            return ZomboidFileSystem.instance.getMediaRootPath();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Splits a per-file comparer error ({@code "<reason>:\n<relPath>[\n<absPath>]"}); null for the
     * protocol-level errors, which no re-download can fix.
     */
    static @Nullable Parsed parse(@Nullable String error) {
        if (error == null) {
            return null;
        }
        for (String reason : FILE_REASONS) {
            String prefix = reason + ":\n";
            if (error.startsWith(prefix)) {
                String rest = error.substring(prefix.length());
                int newline = rest.indexOf('\n');
                String relPath = newline < 0 ? rest : rest.substring(0, newline);
                String absPath = newline < 0 ? "" : rest.substring(newline + 1);
                return new Parsed(reason, relPath, absPath);
            }
        }
        return null;
    }

    static final class Parsed {
        final String reason;
        final String relPath;
        final String absPath;

        Parsed(String reason, String relPath, String absPath) {
            this.reason = reason;
            this.relPath = relPath;
            this.absPath = absPath;
        }
    }

    private static boolean writeHandoff(Parsed parsed) {
        try {
            String cacheDir = ZomboidFileSystem.instance.getCacheDir();
            if (cacheDir == null || cacheDir.isEmpty()) {
                return false;
            }
            Path file = Paths.get(cacheDir, "storm", "launcher", HANDOFF_FILE_NAME);
            Properties props = new Properties();
            props.setProperty("timestampMs", Long.toString(System.currentTimeMillis()));
            props.setProperty("reason", parsed.reason);
            props.setProperty("relPath", parsed.relPath);
            props.setProperty("absPath", parsed.absPath);
            props.setProperty("server", GameClient.ip + ":" + GameClient.port);
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(
                        writer,
                        "Written when a server's file checksum kicked this client;"
                                + " consumed by the Storm Launcher's next join");
            }
            LOGGER.info("Join-failure record written for the launcher: {}", file);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Could not record the checksum kick for the launcher: {}", t.toString());
            return false;
        }
    }
}
