package io.pzstorm.storm.advice.actionmanager;

import io.pzstorm.storm.patch.fixes.NtaDebugLog;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.bytebuddy.asm.Advice;

/** Advice for {@code ActionManager.update()}. */
public class UpdateAdvice {

    private static volatile long lastHeartbeatTime;
    private static final long HEARTBEAT_INTERVAL_MS = 3000;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatTime < HEARTBEAT_INTERVAL_MS) return;
        lastHeartbeatTime = now;

        ConcurrentLinkedQueue<Object> q = NtaDebugLog.getActionsQueue();
        if (q.isEmpty()) return;

        boolean hasOurs = false;
        StringBuilder ourActions = new StringBuilder("[");
        boolean first = true;
        for (Object action : q) {
            if (NtaDebugLog.isAllowedAction(action)) {
                hasOurs = true;
                if (!first) ourActions.append(", ");
                ourActions.append(NtaDebugLog.describe(action));
                first = false;
            }
        }
        ourActions.append("]");

        if (hasOurs) {
            NtaDebugLog.log(
                    NtaDebugLog.side(),
                    "ActionManager.update() heartbeat: our="
                            + ourActions
                            + " totalInQueue="
                            + q.size());
        }
    }
}
