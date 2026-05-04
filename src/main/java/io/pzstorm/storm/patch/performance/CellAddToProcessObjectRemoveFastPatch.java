package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces {@code IsoCell.addToProcessIsoObjectRemove(IsoObject)} with an O(1) sidecar-driven
 * implementation. This is the dominant chunk-unload hotspot — {@code IsoObject.removeFromWorld}
 * calls it for every removed object regardless of type, and the original body always performs one
 * {@code processIsoObject.contains(object)} linear scan even when the object was never a ticking
 * object.
 */
public class CellAddToProcessObjectRemoveFastPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.cellsidecar.";

    public CellAddToProcessObjectRemoveFastPatch() {
        super("zombie.iso.IsoCell");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "AddToProcessIsoObjectRemoveFastAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("addToProcessIsoObjectRemove")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
