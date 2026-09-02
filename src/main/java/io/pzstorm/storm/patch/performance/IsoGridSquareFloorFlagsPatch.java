package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Routes {@code IsoGridSquare.hasNaturalFloor()}, {@code hasSand()} and {@code hasDirt()} through
 * {@link io.pzstorm.storm.iso.StormFloorFlags}: one {@code getFloor()} scan and a per-sprite cached
 * classification instead of up to four scans and a string-compare chain per call. Pairs with {@link
 * IsoSpriteFloorFlagsPatch}. Server-only by registration gate. Fails loud if any of the three
 * methods disappears.
 */
public class IsoGridSquareFloorFlagsPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.IsoGridSquare";
    private static final String ADVICE_PKG = "io.pzstorm.storm.advice.floorflags.";

    public IsoGridSquareFloorFlagsPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        builder = advise(builder, locator, typePool, "hasNaturalFloor", "HasNaturalFloorAdvice");
        builder = advise(builder, locator, typePool, "hasSand", "HasSandAdvice");
        return advise(builder, locator, typePool, "hasDirt", "HasDirtAdvice");
    }

    private static DynamicType.Builder<Object> advise(
            DynamicType.Builder<Object> builder,
            ClassFileLocator locator,
            TypePool typePool,
            String method,
            String advice) {
        if (typePool.describe(TARGET)
                .resolve()
                .getDeclaredMethods()
                .filter(ElementMatchers.named(method).and(ElementMatchers.takesArguments(0)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "IsoGridSquareFloorFlagsPatch: IsoGridSquare no longer declares "
                            + method
                            + "() — re-verify against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE_PKG + advice).resolve(), locator)
                        .on(ElementMatchers.named(method).and(ElementMatchers.takesArguments(0))));
    }
}
