package io.pzstorm.storm.advice.objectremoveworld;

import io.pzstorm.storm.metrics.ObjectRemoveFromWorldMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class IsoObjectRemoveFromWorldAdvice {

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
        ObjectRemoveFromWorldMetrics.recordNanos(System.nanoTime() - startNanos);
    }
}
