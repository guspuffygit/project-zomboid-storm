package io.pzstorm.storm.advice.actionmanager;

import io.pzstorm.storm.patch.fixes.NtaDebugLog;
import net.bytebuddy.asm.Advice;

/** Advice for {@code ActionManager.stop(Action)}. */
public class StopAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) Object action) {
        if (NtaDebugLog.isAllowedAction(action)) {
            NtaDebugLog.log(
                    NtaDebugLog.side(), "ActionManager.stop(): " + NtaDebugLog.describe(action));
        }
    }
}
