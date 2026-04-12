package io.pzstorm.storm.advice.actionmanager;

import io.pzstorm.storm.patch.fixes.NtaDebugLog;
import net.bytebuddy.asm.Advice;

/** Advice for {@code ActionManager.start(Action)}. */
public class StartAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) Object action) {
        if (NtaDebugLog.isAllowedAction(action)) {
            NtaDebugLog.log(
                    NtaDebugLog.side(),
                    "ActionManager.start() ENTER: " + NtaDebugLog.describe(action));
        }
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(@Advice.Argument(0) Object action, @Advice.Thrown Throwable thrown) {
        if (thrown != null && NtaDebugLog.isAllowedAction(action)) {
            NtaDebugLog.log(
                    NtaDebugLog.side(),
                    "ActionManager.start() THREW: "
                            + thrown.getClass().getSimpleName()
                            + ": "
                            + thrown.getMessage()
                            + " action="
                            + NtaDebugLog.describe(action));
        } else if (NtaDebugLog.isAllowedAction(action)) {
            NtaDebugLog.log(
                    NtaDebugLog.side(),
                    "ActionManager.start() EXIT OK: " + NtaDebugLog.describe(action));
        }
    }
}
