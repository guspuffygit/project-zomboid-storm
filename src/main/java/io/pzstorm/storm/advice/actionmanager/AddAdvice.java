package io.pzstorm.storm.advice.actionmanager;

import io.pzstorm.storm.patch.fixes.NtaDebugLog;
import net.bytebuddy.asm.Advice;

/** Advice for {@code ActionManager.add(Action)}. */
public class AddAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) Object action) {
        if (NtaDebugLog.isAllowedAction(action)) {
            NtaDebugLog.log(
                    NtaDebugLog.side(), "ActionManager.add(): " + NtaDebugLog.describe(action));
        }
    }
}
