package io.pzstorm.storm.advice.serverlosiscouldsee;

import io.pzstorm.storm.metrics.ServerLOSIsCouldSeeMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class ServerLOSIsCouldSeeAdvice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        if (!GameServer.server) {
            return 0L;
        }
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long startNanos, @Advice.Return boolean result) {
        if (!GameServer.server) {
            return;
        }
        if (startNanos == 0L) {
            return;
        }
        ServerLOSIsCouldSeeMetrics.recordCall(System.nanoTime() - startNanos, result);
    }
}
