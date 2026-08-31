package io.pzstorm.storm.advice.animalspawn;

import io.pzstorm.storm.metrics.AnimalSpawnMetrics;
import net.bytebuddy.asm.Advice;

/**
 * Exit advice on {@code zombie.characters.animals.AnimalCell.load()}. The {@code fileLoaded} field
 * has just been assigned by the method body: {@code false} means the cell had no {@code
 * apop_x_y.bin} and vanilla therefore ran migration-zone spawning for it. That distinction is what
 * separates "animals never respawn" from "every cell on this map has already been visited".
 */
public class AnimalCellLoadAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.FieldValue("fileLoaded") boolean fileLoaded) {
        AnimalSpawnMetrics.recordCellLoad(fileLoaded);
    }
}
