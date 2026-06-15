package io.pzstorm.storm.advice.playerdownloadserverupdate;

import io.pzstorm.storm.metrics.MainLoopStepTimings;
import io.pzstorm.storm.metrics.PlayerDownloadServerUpdateMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class PlayerDownloadServerUpdateAdvice {

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
        PlayerDownloadServerUpdateMetrics.recordNanos(elapsed);
        MainLoopStepTimings.record("PlayerDownloadServer.update", elapsed);
    }
}
