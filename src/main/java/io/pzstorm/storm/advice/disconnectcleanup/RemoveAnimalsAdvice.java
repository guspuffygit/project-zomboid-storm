package io.pzstorm.storm.advice.disconnectcleanup;

import io.pzstorm.storm.metrics.DisconnectCleanupMetrics;
import io.pzstorm.storm.metrics.MainLoopStepTimings;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class RemoveAnimalsAdvice {

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
        DisconnectCleanupMetrics.recordRemoveAnimalsNanos(elapsed);
        MainLoopStepTimings.record("AnimalInstanceManager.removeAnimals", elapsed);
    }
}
