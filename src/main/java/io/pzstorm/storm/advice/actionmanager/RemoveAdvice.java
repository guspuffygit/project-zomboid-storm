package io.pzstorm.storm.advice.actionmanager;

import io.pzstorm.storm.patch.fixes.NtaDebugLog;
import net.bytebuddy.asm.Advice;

/** Advice for {@code ActionManager.remove(byte, boolean)}. */
public class RemoveAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Argument(0) byte id, @Advice.Argument(1) boolean isCanceled) {
        if (id == 0) return;
        for (Object action : NtaDebugLog.getActionsQueue()) {
            if (NtaDebugLog.getId(action) == id && NtaDebugLog.isAllowedAction(action)) {
                NtaDebugLog.log(
                        NtaDebugLog.side(),
                        "ActionManager.remove() ENTER: id="
                                + id
                                + " isCanceled="
                                + isCanceled
                                + " action="
                                + NtaDebugLog.describe(action));
                return;
            }
        }
    }
}
