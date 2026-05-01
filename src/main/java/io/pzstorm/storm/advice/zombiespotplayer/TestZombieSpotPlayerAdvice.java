package io.pzstorm.storm.advice.zombiespotplayer;

import io.pzstorm.storm.metrics.ZombieSpotPlayerMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class TestZombieSpotPlayerAdvice {

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
        ZombieSpotPlayerMetrics.recordNanos(System.nanoTime() - startNanos);
    }
}
