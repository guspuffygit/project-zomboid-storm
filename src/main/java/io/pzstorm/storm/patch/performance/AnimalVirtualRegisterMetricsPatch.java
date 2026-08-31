package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.metrics.AnimalSpawnMetrics;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Counts virtual animal groups parked into animal chunks, split by whether they came from a fresh
 * migration-zone spawn or from an existing group being re-parked ({@code
 * pz_animal_virtual_registered_total}). Observation only.
 *
 * @see AnimalSpawnMetrics
 */
public class AnimalVirtualRegisterMetricsPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.animalspawn.";

    public AnimalVirtualRegisterMetricsPatch() {
        super("zombie.characters.animals.AnimalManagerWorker");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "AnimalWorkerAddAnimalAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("addAnimal")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
