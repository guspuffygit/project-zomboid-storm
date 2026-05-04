package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces {@code IsoCell.addToStaticUpdaterObjectList(IsoObject)} with an O(1) sidecar-driven
 * implementation, and primes the per-object index sidecar that {@code
 * IsoObjectStaticUpdaterRemoveSubstPatch} consumes for O(1) swap-with-last removal.
 */
public class CellAddToStaticUpdaterFastPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.cellsidecar.";

    public CellAddToStaticUpdaterFastPatch() {
        super("zombie.iso.IsoCell");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "AddToStaticUpdaterFastAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("addToStaticUpdaterObjectList")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
