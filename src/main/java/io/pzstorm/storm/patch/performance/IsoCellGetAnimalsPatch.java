package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Makes {@code IsoCell.getAnimals()} return an {@code ArrayList} instead of a {@code LinkedList},
 * fixing the accidentally-quadratic indexed scan in {@code IsoAnimal.findMotherAndAttach}. See
 * {@link io.pzstorm.storm.advice.isocellgetanimals.GetAnimalsArrayListAdvice} for the mechanism and
 * measurements.
 */
public class IsoCellGetAnimalsPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isocellgetanimals.";

    public IsoCellGetAnimalsPatch() {
        super("zombie.iso.IsoCell");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "GetAnimalsArrayListAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("getAnimals")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
