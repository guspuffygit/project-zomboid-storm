package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.metrics.AnimalSpawnMetrics;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Counts ranch-story evaluations and the ones that win the {@code AnimalRanchChance} roll ({@code
 * pz_animal_ranch_check_total}, {@code pz_animal_ranch_spawn_total}) — the second and last vanilla
 * source of new animals. Observation only.
 *
 * @see AnimalSpawnMetrics
 */
public class RanchAnimalSpawnMetricsPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.animalspawn.";

    public RanchAnimalSpawnMetricsPatch() {
        super("zombie.randomizedWorld.randomizedRanch.RandomizedRanchBase");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "RanchStoryCheckAdvice").resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("checkRanchStory")
                                                .and(ElementMatchers.takesArguments(2))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "RanchRandomizeAdvice").resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("randomizeRanch")
                                                .and(ElementMatchers.takesArguments(2))));
    }
}
