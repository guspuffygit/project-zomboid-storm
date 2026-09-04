package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Halves map work per {@code KahluaTableImpl.rawget}. See {@link
 * io.pzstorm.storm.advice.kahluatablerawget.KahluaTableRawgetAdvice} for the mechanism.
 */
public class KahluaTableRawgetPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.kahluatablerawget.";

    public KahluaTableRawgetPatch() {
        super("se.krka.kahlua.j2se.KahluaTableImpl");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "KahluaTableRawgetAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("rawget")
                                        .and(ElementMatchers.takesArguments(1))
                                        .and(ElementMatchers.takesArgument(0, Object.class))
                                        .and(ElementMatchers.returns(Object.class))));
    }
}
