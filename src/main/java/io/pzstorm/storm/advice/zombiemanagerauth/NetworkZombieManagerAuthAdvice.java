package io.pzstorm.storm.advice.zombiemanagerauth;

import io.pzstorm.storm.metrics.ZombieManagerAuthMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class NetworkZombieManagerAuthAdvice {

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
        ZombieManagerAuthMetrics.recordNanos(System.nanoTime() - startNanos);
    }
}
