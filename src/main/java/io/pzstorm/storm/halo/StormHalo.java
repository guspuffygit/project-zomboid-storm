package io.pzstorm.storm.halo;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;

/**
 * Reusable server&rarr;client helper for showing a temporary speech bubble over a player's head.
 *
 * <p>The server calls one of the {@code setHalo} methods; Storm broadcasts (or unicasts) a {@code
 * ("Storm", "setHalo")} server command, and the client handler in {@code
 * media/lua/client/StormHalo.lua} draws the bubble via PZ's native {@link
 * zombie.characters.IsoGameCharacter#addLineChatElement} &mdash; the same speech-bubble system the
 * game uses for player chat, so it renders over remote players with no chat-log entry.
 *
 * <p>Server-only: every method is a no-op (logged) when not running on the dedicated server. Colors
 * are 0&ndash;255 per channel; omit them for white.
 *
 * <p><b>Caveat:</b> PZ does not render speech bubbles over invisible characters (e.g. a god-mode
 * player), so a bubble targeting such a player will not appear on other clients.
 */
public final class StormHalo {

    /** Server-command module that the client handler listens on. */
    public static final String MODULE = "Storm";

    /** Server-command name that the client handler listens on. */
    public static final String COMMAND = "setHalo";

    private StormHalo() {}

    /** Broadcasts a white speech bubble over {@code target} to every connected client. */
    public static void setHalo(IsoPlayer target, String text) {
        broadcast(buildArgs(target, text, null, null, null));
    }

    /**
     * Broadcasts a colored (0&ndash;255) speech bubble over {@code target} to every connected
     * client.
     */
    public static void setHalo(IsoPlayer target, String text, int r, int g, int b) {
        broadcast(buildArgs(target, text, r, g, b));
    }

    /** Shows a white speech bubble over {@code target}, but only on {@code viewer}'s client. */
    public static void setHaloFor(IsoPlayer viewer, IsoPlayer target, String text) {
        unicast(viewer, buildArgs(target, text, null, null, null));
    }

    /**
     * Shows a colored (0&ndash;255) speech bubble over {@code target}, but only on {@code viewer}'s
     * client.
     */
    public static void setHaloFor(
            IsoPlayer viewer, IsoPlayer target, String text, int r, int g, int b) {
        unicast(viewer, buildArgs(target, text, r, g, b));
    }

    private static KahluaTable buildArgs(
            IsoPlayer target, String text, Integer r, Integer g, Integer b) {
        if (target == null || text == null) {
            LOGGER.warn("StormHalo: null target or text; ignoring setHalo request.");
            return null;
        }
        KahluaTable args = LuaManager.platform.newTable();
        args.rawset("onlineID", (double) target.getOnlineID());
        args.rawset("text", text);
        if (r != null && g != null && b != null) {
            args.rawset("r", (double) r);
            args.rawset("g", (double) g);
            args.rawset("b", (double) b);
        }
        return args;
    }

    private static void broadcast(KahluaTable args) {
        if (args == null) {
            return;
        }
        if (!GameServer.server) {
            LOGGER.warn("StormHalo.setHalo called off the dedicated server; ignoring.");
            return;
        }
        GameServer.sendServerCommand(MODULE, COMMAND, args);
    }

    private static void unicast(IsoPlayer viewer, KahluaTable args) {
        if (args == null) {
            return;
        }
        if (viewer == null) {
            LOGGER.warn("StormHalo.setHaloFor: null viewer; ignoring.");
            return;
        }
        if (!GameServer.server) {
            LOGGER.warn("StormHalo.setHaloFor called off the dedicated server; ignoring.");
            return;
        }
        GameServer.sendServerCommand(viewer, MODULE, COMMAND, args);
    }
}
