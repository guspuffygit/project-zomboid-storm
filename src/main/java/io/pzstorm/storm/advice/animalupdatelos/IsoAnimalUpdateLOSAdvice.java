package io.pzstorm.storm.advice.animalupdatelos;

import io.pzstorm.storm.metrics.AnimalUpdateLOSMetrics;
import io.pzstorm.storm.patch.performance.AnimalLOSTickInterval;
import net.bytebuddy.asm.Advice;
import zombie.characters.animals.IsoAnimal;
import zombie.network.GameServer;

public class IsoAnimalUpdateLOSAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnDefaultValue.class)
    public static long onEnter(@Advice.This IsoAnimal self) {
        if (!GameServer.server) {
            return -1L;
        }
        if (!AnimalLOSTickInterval.shouldRunThisTick(self.getAnimalID())) {
            return 0L;
        }
        long start = System.nanoTime();
        return start <= 0L ? 1L : start;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long startNanos) {
        if (startNanos <= 0L) {
            return;
        }
        AnimalUpdateLOSMetrics.recordNanos(System.nanoTime() - startNanos);
    }
}
