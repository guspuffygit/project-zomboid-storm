package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import zombie.characters.IsoPlayer;
import zombie.core.Transaction;
import zombie.core.raknet.UdpConnection;
import zombie.network.fields.character.Player;
import zombie.network.packets.NetTimedActionPacket;

/**
 * Runtime logic for {@link NetTimedActionPacketPatch}, kept in a separate class so that {@code
 * NetTimedActionPacketPatch} itself never references any game type.
 *
 * <p><b>Why the split matters:</b> patch classes are linked while {@code StormClassTransformers}'s
 * static registration block runs, before {@code StormBootstrap.hasLoaded()} is true. Verifying a
 * method that touches {@code NetTimedActionPacket} there loads it through {@code StormClassLoader}
 * <em>untransformed</em> and silently disables every transformer registered for it (this patch and
 * its {@code PacketReceivedPatch}). This class is only loaded when the woven advice first executes,
 * long after all transformers are registered.
 *
 * @see NetTimedActionPacketPatch
 */
public class NetTimedActionPacketFix {

    /**
     * Action types already reported by {@link #reportMalformedAction}; keeps the log to one line
     * per broken action type instead of one per attempt.
     */
    private static final Set<String> reportedMalformedTypes = ConcurrentHashMap.newKeySet();

    /** Runs on entry to {@code NetTimedActionPacket.processServer()}; never changes its outcome. */
    public static void onProcessServer(NetTimedActionPacket packet, UdpConnection connection) {
        Transaction.TransactionState state = NtaDebugLog.getState(packet);
        if (NtaDebugLog.isAllowedConnection(connection)) {
            NtaDebugLog.log(
                    "SERVER",
                    "processServer ENTER: state="
                            + state
                            + " steamId="
                            + connection.getSteamId()
                            + " packet="
                            + NtaDebugLog.describe(packet));
        }
        if (state == Transaction.TransactionState.Request) {
            reportMalformedAction(packet, packet.action != null);
        }
    }

    /**
     * Names the timed action that {@code NetTimedActionPacket.processServer} is about to reject.
     *
     * <p>The server reconstructs a client's timed action by calling {@code <Type>.new(...)} with
     * values the client read out of the action instance using the <em>constructor's parameter
     * names</em> as field names ({@code NetTimedAction.set}), then re-supplied positionally ({@code
     * NetTimedAction.parse}). Vanilla rejects the request when {@code action} is null or when
     * {@code action.character} is not the connection's own player, and the only trace it leaves is
     * a {@code DebugType.Action} line that names no cause. Three things routinely trip that gate:
     *
     * <ul>
     *   <li>The class is not loaded on the server (timed actions under {@code media/lua/client/}
     *       are checksum-only there), or its {@code new} threw. {@code action} comes back null.
     *   <li>{@code character} came back nil. The constructor's first parameter is not named {@code
     *       character}, or an earlier argument's type is missing from {@code
     *       PZNetKahluaTableImpl.getValueByte} and was dropped from the wire, shifting the rest.
     *   <li>{@code character} is some other player, so the client asked the server to run an action
     *       on a character its connection does not own.
     * </ul>
     */
    private static void reportMalformedAction(NetTimedActionPacket packet, boolean hasAction) {
        try {
            String type = packet.type == null || packet.type.isEmpty() ? "?" : packet.type;
            if (!hasAction) {
                if (reportedMalformedTypes.add("missing:" + type)) {
                    LOGGER.warn(
                            "{} timed action '{}' could not be rebuilt server-side (class not"
                                    + " loaded on the server, or its new() failed) - vanilla"
                                    + " rejects it. Move the action out of media/lua/client/ into"
                                    + " media/lua/shared/.",
                            NtaDebugLog.PREFIX,
                            type);
                }
                return;
            }
            Object character = packet.action.rawget("character");
            if (character == null) {
                if (reportedMalformedTypes.add("character:" + type)) {
                    LOGGER.warn(
                            "{} timed action '{}' rebuilt server-side with a nil character -"
                                    + " vanilla rejects it. Check that {}:new() takes 'character'"
                                    + " first and stores every parameter under its own name, and"
                                    + " that no earlier parameter holds a type PZNetKahluaTableImpl"
                                    + " cannot serialize (a Lua function, an enum, a Fluid) - those"
                                    + " are dropped from the wire and shift the rest.",
                            NtaDebugLog.PREFIX,
                            type,
                            type);
                }
                return;
            }
            Player owner = NtaDebugLog.getPlayer(packet);
            IsoPlayer connectionPlayer = owner == null ? null : owner.getPlayer();
            if (character != connectionPlayer && reportedMalformedTypes.add("mismatch:" + type)) {
                LOGGER.warn(
                        "{} timed action '{}' rebuilt server-side with a character that is not"
                                + " the sending connection's player ({} vs {}) - vanilla rejects"
                                + " it. The client passed another character to {}:new().",
                        NtaDebugLog.PREFIX,
                        type,
                        character instanceof IsoPlayer p ? p.getUsername() : character,
                        connectionPlayer == null ? "null" : connectionPlayer.getUsername(),
                        type);
            }
        } catch (Exception ignored) {
            // diagnostics must never take down packet handling
        }
    }
}
