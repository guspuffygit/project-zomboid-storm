package io.pzstorm.storm.advice.animalspawn;

import io.pzstorm.storm.metrics.AnimalSpawnMetrics;
import net.bytebuddy.asm.Advice;

/**
 * Entry advice on {@code RandomizedRanchBase.randomizeRanch(Zone, DesignationZoneAnimal)}, reached
 * only after the {@code AnimalRanchChance} roll succeeds. One increment = one ranch populated.
 */
public class RanchRandomizeAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        AnimalSpawnMetrics.recordRanchSpawn();
    }
}
