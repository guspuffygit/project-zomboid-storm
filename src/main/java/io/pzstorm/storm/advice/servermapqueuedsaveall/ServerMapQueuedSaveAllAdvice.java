package io.pzstorm.storm.advice.servermapqueuedsaveall;

import io.pzstorm.storm.metrics.MainLoopStepTimings;
import io.pzstorm.storm.patch.performance.StormPoolCompaction;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class ServerMapQueuedSaveAllAdvice {

    @Advice.OnMethodEnter
    public static long onEnter(@Advice.Argument(0) boolean quit) {
        if (!GameServer.server) {
            return 0L;
        }
        // quit=true runs on the shutdown-hook thread, and Pool's stacks are ThreadLocal
        if (!quit) {
            StormPoolCompaction.compactAtSaveWindow();
        }
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long startNanos) {
        if (!GameServer.server) {
            return;
        }
        if (startNanos == 0L) {
            return;
        }
        MainLoopStepTimings.record("ServerMap.QueuedSaveAll", System.nanoTime() - startNanos);
    }
}
