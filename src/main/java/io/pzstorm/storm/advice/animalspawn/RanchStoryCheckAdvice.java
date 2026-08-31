package io.pzstorm.storm.advice.animalspawn;

import io.pzstorm.storm.metrics.AnimalSpawnMetrics;
import net.bytebuddy.asm.Advice;

/**
 * Exit advice on {@code RandomizedRanchBase.checkRanchStory(Zone, boolean)}, called for every zone
 * of every chunk that streams in. The return value is {@code true} only for a fully streamed {@code
 * Ranch} zone that had never been seen — i.e. the calls that actually roll {@code
 * AnimalRanchChance}.
 */
public class RanchStoryCheckAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.Return boolean processed) {
        AnimalSpawnMetrics.recordRanchCheck(processed);
    }
}
