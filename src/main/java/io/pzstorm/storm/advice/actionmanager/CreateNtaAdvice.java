package io.pzstorm.storm.advice.actionmanager;

import io.pzstorm.storm.patch.fixes.NtaDebugLog;
import net.bytebuddy.asm.Advice;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;

/** Advice for {@code ActionManager.createNetTimedAction(IsoPlayer, KahluaTable)}. */
public class CreateNtaAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Argument(0) IsoPlayer player,
            @Advice.Argument(1) KahluaTable actionTable,
            @Advice.Return byte transactionId) {
        if (!NtaDebugLog.isAllowedClient()) return;

        String type = "unknown";
        String name = "unknown";
        try {
            if (actionTable != null && actionTable.getMetatable() != null) {
                Object typeObj = actionTable.getMetatable().rawget("Type");
                if (typeObj != null) type = typeObj.toString();
            }
            if (actionTable != null) {
                Object nameObj = actionTable.rawget("name");
                if (nameObj != null) name = nameObj.toString();
            }
        } catch (Exception e) {
            // ignore
        }

        String playerName = (player != null) ? player.getUsername() : "?";
        NtaDebugLog.log(
                "CLIENT",
                "createNetTimedAction(): transactionId="
                        + transactionId
                        + " type="
                        + type
                        + " name="
                        + name
                        + " player="
                        + playerName
                        + (transactionId == 0 ? " WARNING: id=0 means send failed!" : ""));
    }
}
