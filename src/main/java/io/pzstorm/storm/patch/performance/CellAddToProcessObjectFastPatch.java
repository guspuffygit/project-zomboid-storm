package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces {@code IsoCell.addToProcessIsoObject(IsoObject)} with an O(1) sidecar-driven
 * implementation. See {@code docs/recording2-jfr-analysis.md} §7.3 for the JFR finding that
 * motivated this patch (~10.86% of all server CPU was {@code ArrayList.indexOfRange}, dominated by
 * this and a sibling method).
 */
public class CellAddToProcessObjectFastPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.cellsidecar.";

    public CellAddToProcessObjectFastPatch() {
        super("zombie.iso.IsoCell");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "AddToProcessIsoObjectFastAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("addToProcessIsoObject")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
