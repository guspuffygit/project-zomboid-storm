package io.pzstorm.storm.advice.actionmanager;

import io.pzstorm.storm.patch.fixes.NtaDebugLog;
import net.bytebuddy.asm.Advice;
import zombie.network.fields.character.PlayerID;

/** Advice for {@code ActionManager.stopPlayerActions(PlayerID)}. */
public class StopPlayerActionsAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static String onEnter(@Advice.Argument(0) PlayerID playerId) {
        if (NtaDebugLog.isAllowedPlayerId(playerId)) {
            String before = NtaDebugLog.describePlayerActions(playerId);
            NtaDebugLog.log(
                    NtaDebugLog.side(),
                    "ActionManager.stopPlayerActions() ENTER: playerId="
                            + playerId.getID()
                            + " player="
                            + (playerId.getPlayer() != null
                                    ? playerId.getPlayer().getUsername()
                                    : "?")
                            + " actions="
                            + before);
            return before;
        }
        return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) PlayerID playerId, @Advice.Enter String before) {
        if (before != null) {
            NtaDebugLog.log(
                    NtaDebugLog.side(),
                    "ActionManager.stopPlayerActions() EXIT: playerId="
                            + playerId.getID()
                            + " remainingActions="
                            + NtaDebugLog.describePlayerActions(playerId));
        }
    }
}
