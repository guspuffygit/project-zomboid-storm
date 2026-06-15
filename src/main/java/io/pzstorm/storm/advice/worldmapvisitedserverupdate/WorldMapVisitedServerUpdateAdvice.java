package io.pzstorm.storm.advice.worldmapvisitedserverupdate;

import io.pzstorm.storm.metrics.MainLoopStepTimings;
import io.pzstorm.storm.metrics.WorldMapVisitedServerUpdateMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class WorldMapVisitedServerUpdateAdvice {

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
        WorldMapVisitedServerUpdateMetrics.recordNanos(elapsed);
        MainLoopStepTimings.record("WorldMapVisitedServer.update", elapsed);
    }
}
