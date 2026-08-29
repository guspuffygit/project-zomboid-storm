package io.pzstorm.storm.patch.fixes;

import java.lang.reflect.Field;
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
}
