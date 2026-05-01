package io.pzstorm.storm.advice.entitymanagerupdate;

import io.pzstorm.storm.metrics.EntityManagerUpdateMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class GameEntityManagerUpdateAdvice {

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
        EntityManagerUpdateMetrics.recordNanos(System.nanoTime() - startNanos);
    }
}
