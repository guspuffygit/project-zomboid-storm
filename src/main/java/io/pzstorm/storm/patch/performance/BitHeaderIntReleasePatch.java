package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/** Instruments {@code BitHeader$BitHeaderInt.release()} for volume counting. */
public class BitHeaderIntReleasePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.bitheader.";

    public BitHeaderIntReleasePatch() {
        super("zombie.util.io.BitHeader$BitHeaderInt");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "BitHeaderIntReleaseAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("release")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
