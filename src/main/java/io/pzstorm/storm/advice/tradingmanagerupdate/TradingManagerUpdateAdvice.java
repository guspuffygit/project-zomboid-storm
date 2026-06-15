package io.pzstorm.storm.advice.tradingmanagerupdate;

import io.pzstorm.storm.metrics.MainLoopStepTimings;
import io.pzstorm.storm.metrics.TradingManagerUpdateMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class TradingManagerUpdateAdvice {

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
        TradingManagerUpdateMetrics.recordNanos(elapsed);
        MainLoopStepTimings.record("TradingManager.update", elapsed);
    }
}
