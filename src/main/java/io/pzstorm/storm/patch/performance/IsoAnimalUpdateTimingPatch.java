package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Instruments {@code IsoAnimal.update()} with timing advice. Per-call wall-clock nanoseconds are
 * accumulated by {@code io.pzstorm.storm.metrics.AnimalUpdateMetrics} and reported every 60s.
 *
 * <p>Targets the no-arg {@code update()} on {@code zombie.characters.animals.IsoAnimal} (line 369
 * in the decompiled source), which is the per-tick entry point invoked from {@code
 * MovingObjectUpdateSchedulerUpdateBucket.update()} and dominates main-thread CPU under load.
 */
public class IsoAnimalUpdateTimingPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.animalupdatetiming.";

    public IsoAnimalUpdateTimingPatch() {
        super("zombie.characters.animals.IsoAnimal");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "IsoAnimalUpdateTimingAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("update")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
