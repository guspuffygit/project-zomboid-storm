package io.pzstorm.storm.advice.warmanagerupdate;

import io.pzstorm.storm.metrics.MainLoopStepTimings;
import io.pzstorm.storm.metrics.WarManagerUpdateMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class WarManagerUpdateAdvice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        if (!GameServer.server) {
            return 0L;
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
        long elapsed = System.nanoTime() - startNanos;
        WarManagerUpdateMetrics.recordNanos(elapsed);
        MainLoopStepTimings.record("WarManager.update", elapsed);
    }
}
