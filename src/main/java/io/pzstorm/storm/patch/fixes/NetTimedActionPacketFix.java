package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import zombie.core.ActionManager;
import zombie.core.NetTimedAction;
import zombie.core.Transaction;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.PacketTypes;
import zombie.network.fields.character.PlayerID;
import zombie.network.packets.NetTimedActionPacket;

/**
 * Runtime logic for {@link NetTimedActionPacketPatch}, kept in a separate class so that {@code
 * NetTimedActionPacketPatch} itself never references any game type.
 *
 * <p><b>Why the split matters:</b> patch classes are linked while {@code StormClassTransformers}'s
 * static registration block runs, before {@code StormBootstrap.hasLoaded()} is true. Bytecode
 * verification of a method like {@link #processServerFixed} must prove {@code NetTimedActionPacket
 * <: NetTimedAction} (for {@code act.copyFrom(packet)}), which loads {@code NetTimedActionPacket}
 * through {@code StormClassLoader} — defining it <em>untransformed</em> and silently disabling
 * every transformer registered for it (this patch and its {@code PacketReceivedPatch}). This class
 * is only loaded when the woven advice first executes, long after all transformers are registered.
 *
 * @see NetTimedActionPacketPatch
 */
public class NetTimedActionPacketFix {

    private static volatile Field stateField;
    private static volatile Field idField;
    private static volatile Field playerIdField;

    /**
     * Action types already reported by {@link #reportMalformedAction}; keeps the log to one line
     * per broken action type instead of one per attempt.
     */
    private static final Set<String> reportedMalformedTypes = ConcurrentHashMap.newKeySet();

    private static synchronized void initFieldHandles() throws ReflectiveOperationException {
        if (stateField != null) return;
        Class<?> actionClass = Class.forName("zombie.core.Action");
        Field sf = actionClass.getDeclaredField("state");
        sf.setAccessible(true);
        Field idf = actionClass.getDeclaredField("id");
        idf.setAccessible(true);
        Field pf = actionClass.getDeclaredField("playerId");
        pf.setAccessible(true);
        // Publish all at once — volatile write of stateField acts as release fence
        idField = idf;
        playerIdField = pf;
        stateField = sf;
    }

    /**
     * Corrected implementation of {@code NetTimedActionPacket.processServer()}. Identical to
     * vanilla except that {@code act.write(bbw)} is used instead of {@code this.write(bbw)} in both
     * the Accept and Reject response paths.
     *
     * <p>The private {@code getAction()} helper is replicated using the public {@link
     * ActionManager#getAction(byte, PlayerID)} and {@link NetTimedAction#copyFrom(NetTimedAction)}.
     *
     * @return {@code true} if the fix ran (skip original), {@code false} if the player is not in
     *     the allowed set (fall through to vanilla).
     */
    public static boolean processServerFixed(NetTimedActionPacket packet, UdpConnection connection)
            throws ReflectiveOperationException {
        if (stateField == null) {
            initFieldHandles();
        }

        Transaction.TransactionState state = (Transaction.TransactionState) stateField.get(packet);
        byte id = idField.getByte(packet);
        PlayerID playerId = (PlayerID) playerIdField.get(packet);

        boolean shouldLog = NtaDebugLog.isAllowedConnection(connection);

        if (shouldLog) {
            NtaDebugLog.log(
                    "SERVER",
                    "processServer ENTER: state="
                            + state
                            + " id="
                            + id
                            + " steamId="
                            + connection.getSteamId()
                            + " packet="
                            + NtaDebugLog.describe(packet));
        }

        if (state == Transaction.TransactionState.Request) {
            boolean consistent = packet.isConsistent(connection);
            boolean hasAction = packet.action != null;

            reportMalformedAction(packet, hasAction);

            if (shouldLog) {
                NtaDebugLog.log(
                        "SERVER",
                        "processServer: isConsistent="
                                + consistent
                                + " hasAction="
                                + hasAction
                                + " -> "
                                + (consistent && hasAction ? "ACCEPT" : "REJECT")
                                + " path");
            }

            if (consistent && hasAction) {
                // --- ACCEPT PATH ---
                if (shouldLog) {
                    NtaDebugLog.log(
                            "SERVER",
                            "processServer ACCEPT: before stopPlayerActions, currentActions="
                                    + NtaDebugLog.describePlayerActions(playerId));
                }

                ActionManager.stopPlayerActions(playerId);

                if (shouldLog) {
                    NtaDebugLog.log(
                            "SERVER",
                            "processServer ACCEPT: after stopPlayerActions, remainingActions="
                                    + NtaDebugLog.describePlayerActions(playerId));
                }

                NetTimedAction act = ActionManager.getAction(id, playerId);
                boolean existingAction = act != null;
                if (act == null) {
                    act = new NetTimedAction();
                }
                act.copyFrom(packet);

                if (shouldLog) {
                    NtaDebugLog.log(
                            "SERVER",
                            "processServer ACCEPT: before start(), existingAction="
                                    + existingAction
                                    + " act="
                                    + NtaDebugLog.describe(act));
                }

                try {
                    ActionManager.start(act);
                } catch (Exception e) {
                    if (shouldLog) {
                        NtaDebugLog.log(
                                "SERVER",
                                "processServer ACCEPT: start() THREW "
                                        + e.getClass().getSimpleName()
                                        + ": "
                                        + e.getMessage()
                                        + " - action lost, client will hang!");
                    }
                    throw e;
                }

                act.setState(Transaction.TransactionState.Accept);

                if (shouldLog) {
                    NtaDebugLog.log(
                            "SERVER",
                            "processServer ACCEPT: after start+setState, act="
                                    + NtaDebugLog.describe(act));
                }

                ByteBufferWriter bbw = connection.startPacket();
                PacketTypes.PacketType.NetTimedAction.doPacket(bbw);
                act.write(bbw); // FIX: write act (state=Accept, with duration) not this
                PacketTypes.PacketType.NetTimedAction.send(connection);

                if (shouldLog) {
                    NtaDebugLog.log(
                            "SERVER",
                            "processServer ACCEPT: response sent (act.write with state=Accept)");
                }
            } else {
                // --- REJECT PATH ---
                if (shouldLog) {
                    NtaDebugLog.log(
                            "SERVER",
                            "processServer REJECT: creating reject response for id=" + id);
                }

                NetTimedAction act = ActionManager.getAction(id, playerId);
                if (act == null) {
                    act = new NetTimedAction();
                }
                act.copyFrom(packet);
                act.setState(Transaction.TransactionState.Reject);

                if (shouldLog) {
                    NtaDebugLog.log(
                            "SERVER", "processServer REJECT: act=" + NtaDebugLog.describe(act));
                }

                ByteBufferWriter bbw = connection.startPacket();
                PacketTypes.PacketType.NetTimedAction.doPacket(bbw);
                act.write(bbw); // FIX: write act (state=Reject) not this
                PacketTypes.PacketType.NetTimedAction.send(connection);

                if (shouldLog) {
                    NtaDebugLog.log(
                            "SERVER",
                            "processServer REJECT: response sent (act.write with state=Reject)");
                }
            }
        } else if (Transaction.TransactionState.Reject == state) {
            // --- CLIENT REJECT ACKNOWLEDGEMENT ---
            if (shouldLog) {
                NtaDebugLog.log(
                        "SERVER",
                        "processServer CLIENT_REJECT_ACK: id="
                                + id
                                + " currentActions="
                                + NtaDebugLog.describePlayerActions(playerId));
            }

            NetTimedAction act = ActionManager.getAction(id, playerId);
            if (act == null) {
                act = new NetTimedAction();
            }
            act.copyFrom(packet);

            if (shouldLog) {
                NtaDebugLog.log(
                        "SERVER",
                        "processServer CLIENT_REJECT_ACK: stopping act="
                                + NtaDebugLog.describe(act));
            }

            ActionManager.stop(act);

            if (shouldLog) {
                NtaDebugLog.log(
                        "SERVER",
                        "processServer CLIENT_REJECT_ACK: done, remainingActions="
                                + NtaDebugLog.describePlayerActions(playerId));
            }
        } else {
            // Unexpected state
            if (shouldLog) {
                NtaDebugLog.log(
                        "SERVER",
                        "processServer: UNEXPECTED state="
                                + state
                                + " id="
                                + id
                                + " packet="
                                + NtaDebugLog.describe(packet));
            }
        }

        return true;
    }

    /**
     * Names the offending timed action when {@code NetTimedAction.parse} fails to rebuild it
     * server-side.
     *
     * <p>The server reconstructs a client's timed action by calling {@code <Type>.new(...)} with
     * values the client read out of the action instance using the <em>constructor's parameter
     * names</em> as field names ({@code NetTimedAction.set}), then re-supplied positionally ({@code
     * NetTimedAction.parse}). Two things routinely break that round trip and both are silent in
     * vanilla:
     *
     * <ul>
     *   <li>The class is not loaded on the server (timed actions under {@code media/lua/client/}
     *       are checksum-only there), or its {@code new} threw — {@code action} comes back null and
     *       the action is rejected with no explanation.
     *   <li>{@code character} came back nil — the constructor's first parameter is not named {@code
     *       character}, or an earlier argument's type is missing from {@code
     *       PZNetKahluaTableImpl.getValueByte} and was dropped from the wire, shifting the rest.
     *       Every later Lua call then dies on {@code self.character}, starting with {@code
     *       ISBaseTimedAction:adjustMaxTime}.
     * </ul>
     */
    private static void reportMalformedAction(NetTimedActionPacket packet, boolean hasAction) {
        try {
            String type = packet.type == null || packet.type.isEmpty() ? "?" : packet.type;
            if (!hasAction) {
                if (reportedMalformedTypes.add("missing:" + type)) {
                    LOGGER.warn(
                            "{} timed action '{}' could not be rebuilt server-side (class not"
                                    + " loaded on the server, or its new() failed) - rejecting it. Move"
                                    + " the action out of media/lua/client/ into media/lua/shared/.",
                            NtaDebugLog.PREFIX,
                            type);
                }
                return;
            }
            if (packet.action.rawget("character") == null
                    && reportedMalformedTypes.add("character:" + type)) {
                LOGGER.warn(
                        "{} timed action '{}' rebuilt server-side with a nil character - its"
                                + " adjustMaxTime/isValid/complete will all fail. Check that {}:new()"
                                + " takes 'character' first and stores every parameter under its own"
                                + " name, and that no earlier parameter holds a type"
                                + " PZNetKahluaTableImpl cannot serialize (a Lua function, an enum, a"
                                + " Fluid) - those are dropped from the wire and shift the rest.",
                        NtaDebugLog.PREFIX,
                        type,
                        type);
            }
        } catch (Exception ignored) {
            // diagnostics must never take down packet handling
        }
    }
}
