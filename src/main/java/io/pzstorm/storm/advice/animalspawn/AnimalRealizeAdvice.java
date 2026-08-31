package io.pzstorm.storm.advice.animalspawn;

import io.pzstorm.storm.metrics.AnimalSpawnMetrics;
import net.bytebuddy.asm.Advice;

/**
 * Entry advice on {@code zombie.characters.animals.AnimalManagerMain.fromWorker(ArrayList)} — the
 * virtual → real half of the round trip. Vanilla skips any group whose grid square is not loaded
 * without a log line, so {@link AnimalSpawnMetrics} repeats the lookup to count the drops.
 */
public class AnimalRealizeAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Object animals) {
        AnimalSpawnMetrics.recordRealizeBatch(animals);
    }
}
