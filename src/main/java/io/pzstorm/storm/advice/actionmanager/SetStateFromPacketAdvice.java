package io.pzstorm.storm.advice.actionmanager;

import io.pzstorm.storm.patch.fixes.NtaDebugLog;
import net.bytebuddy.asm.Advice;
import zombie.core.NetTimedAction;
import zombie.core.Transaction;
import zombie.network.fields.character.PlayerID;

/** Advice for {@code ActionManager.setStateFromPacket(Action)}. */
public class SetStateFromPacketAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) Object packet) {
        if (!NtaDebugLog.isAllowedAction(packet)) return;

        byte pktId = NtaDebugLog.getId(packet);
        Transaction.TransactionState pktState = NtaDebugLog.getState(packet);
        PlayerID pktPid = NtaDebugLog.getPlayerId(packet);
        long pktDuration = (packet instanceof NetTimedAction nta) ? nta.duration : -1;

        boolean found = false;
        for (Object action : NtaDebugLog.getActionsQueue()) {
            byte actionId = NtaDebugLog.getId(action);
            PlayerID actionPid = NtaDebugLog.getPlayerId(action);
            if (actionId == pktId
                    && actionPid != null
                    && pktPid != null
                    && actionPid.getID() == pktPid.getID()) {
                Transaction.TransactionState currentState = NtaDebugLog.getState(action);
                NtaDebugLog.log(
                        "CLIENT",
                        "setStateFromPacket: MATCH id="
                                + pktId
                                + " currentState="
                                + currentState
                                + " -> newState="
                                + pktState
                                + (pktState == Transaction.TransactionState.Accept
                                        ? " duration=" + pktDuration
                                        : "")
                                + " action="
                                + NtaDebugLog.describe(action));
                found = true;
                break;
            }
        }
        if (!found) {
            NtaDebugLog.log(
                    "CLIENT",
                    "setStateFromPacket: NO MATCH for id="
                            + pktId
                            + " state="
                            + pktState
                            + " playerId="
                            + (pktPid != null ? pktPid.getID() : -1)
                            + " queue="
                            + NtaDebugLog.describeQueue());
        }
    }
}
