package io.pzstorm.storm.advice.animalsync;

import io.pzstorm.storm.metrics.AnimalSyncMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class AnimalSyncManagerUpdateAdvice {

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
        AnimalSyncMetrics.recordNanos(System.nanoTime() - startNanos);
    }
}
