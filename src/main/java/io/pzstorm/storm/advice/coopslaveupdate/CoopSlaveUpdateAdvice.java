package io.pzstorm.storm.advice.coopslaveupdate;

import io.pzstorm.storm.metrics.CoopSlaveUpdateMetrics;
import io.pzstorm.storm.metrics.MainLoopStepTimings;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class CoopSlaveUpdateAdvice {

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
        CoopSlaveUpdateMetrics.recordNanos(elapsed);
        MainLoopStepTimings.record("CoopSlave.update", elapsed);
    }
}
