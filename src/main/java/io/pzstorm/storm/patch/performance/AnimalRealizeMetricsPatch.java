package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.metrics.AnimalSpawnMetrics;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Counts virtual animal groups handed back to the world, and the ones vanilla silently drops
 * because their grid square is not loaded ({@code pz_animal_realize_total}, {@code
 * pz_animal_realize_animals_total}). Observation only.
 *
 * @see AnimalSpawnMetrics
 */
public class AnimalRealizeMetricsPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.animalspawn.";

    public AnimalRealizeMetricsPatch() {
        super("zombie.characters.animals.AnimalManagerMain");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "AnimalRealizeAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("fromWorker")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
