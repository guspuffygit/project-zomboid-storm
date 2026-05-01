package io.pzstorm.storm.advice.animalupdatelos;

import io.pzstorm.storm.metrics.AnimalUpdateLOSMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class IsoAnimalUpdateLOSAdvice {

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
        AnimalUpdateLOSMetrics.recordNanos(System.nanoTime() - startNanos);
    }
}
