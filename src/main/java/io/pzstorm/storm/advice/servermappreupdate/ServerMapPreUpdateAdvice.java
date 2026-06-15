package io.pzstorm.storm.advice.servermappreupdate;

import io.pzstorm.storm.metrics.MainLoopStepTimings;
import io.pzstorm.storm.metrics.ServerMapPreUpdateMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class ServerMapPreUpdateAdvice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        if (!GameServer.server) {
            return 0L;
        }
        MainLoopStepTimings.beginTick();
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
        ServerMapPreUpdateMetrics.recordNanos(elapsed);
        MainLoopStepTimings.record("ServerMap.preupdate", elapsed);
    }
}
