package io.pzstorm.storm.advice.actionmanager;

import io.pzstorm.storm.patch.fixes.NtaDebugLog;
import net.bytebuddy.asm.Advice;

/** Advice for {@code ActionManager.isDone(byte)}. */
public class IsDoneAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) byte id, @Advice.Return boolean result) {
        if (!result || !NtaDebugLog.isAllowedClient()) return;

        boolean anyMatch = false;
        for (Object action : NtaDebugLog.getActionsQueue()) {
            if (NtaDebugLog.getId(action) == id) {
                anyMatch = true;
                break;
            }
        }
        if (!anyMatch) {
            NtaDebugLog.log(
                    "CLIENT",
                    "isDone("
                            + id
                            + ") = TRUE via VACUOUS TRUTH (no matching action, queue non-empty)! queue="
                            + NtaDebugLog.describeQueue());
        } else {
            NtaDebugLog.log(
                    "CLIENT",
                    "isDone(" + id + ") = TRUE (state=Done). queue=" + NtaDebugLog.describeQueue());
        }
    }
}
