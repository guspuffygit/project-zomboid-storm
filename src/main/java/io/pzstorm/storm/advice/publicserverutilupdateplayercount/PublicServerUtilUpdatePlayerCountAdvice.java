package io.pzstorm.storm.advice.publicserverutilupdateplayercount;

import io.pzstorm.storm.metrics.MainLoopStepTimings;
import io.pzstorm.storm.metrics.PublicServerUtilUpdatePlayerCountMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class PublicServerUtilUpdatePlayerCountAdvice {

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
        PublicServerUtilUpdatePlayerCountMetrics.recordNanos(elapsed);
        MainLoopStepTimings.record("PublicServerUtil.updatePlayerCountIfChanged", elapsed);
    }
}
