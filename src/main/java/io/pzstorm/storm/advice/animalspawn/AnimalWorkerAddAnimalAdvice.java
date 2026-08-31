package io.pzstorm.storm.advice.animalspawn;

import io.pzstorm.storm.metrics.AnimalSpawnMetrics;
import net.bytebuddy.asm.Advice;

/**
 * Entry advice on {@code zombie.characters.animals.AnimalManagerWorker.addAnimal(VirtualAnimal)},
 * the single funnel through which every virtual animal group is parked into an animal chunk —
 * whether freshly spawned by a migration zone or round-tripped out of the loaded world. {@link
 * AnimalSpawnMetrics} separates the two by the marker {@link AnimalZoneSpawnAdvice} sets.
 */
public class AnimalWorkerAddAnimalAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Object virtualAnimal) {
        AnimalSpawnMetrics.recordVirtualRegistered(virtualAnimal);
    }
}
