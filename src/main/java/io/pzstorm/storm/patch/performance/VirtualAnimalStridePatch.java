package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Strides the static {@code AnimalZones.updateVirtualAnimals()} pass to every Nth tick, with {@code
 * GameTime.perObjectMultiplier} compensation on executing ticks. See {@link
 * VirtualAnimalTickInterval} for the behavior analysis; configured via the {@code
 * Storm.VirtualAnimalTickInterval} sandbox option.
 *
 * <p>Must be registered <em>after</em> {@code AnimalZonesUpdateVirtualAnimalsPatch} so this skip
 * wraps outside the timing advice — skipped ticks then record no timing sample and the {@code
 * AnimalZones.updateVirtualAnimals} step reflects real work only.
 */
public class VirtualAnimalStridePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.virtualanimalstride.";

    public VirtualAnimalStridePatch() {
        super("zombie.characters.animals.AnimalZones");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "AnimalZonesVirtualAnimalStrideAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("updateVirtualAnimals")
                                        .and(ElementMatchers.takesArguments(0))
                                        .and(ElementMatchers.isStatic())));
    }
}
