package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Makes {@code RandInterface.AdjustForFramerate(int)} respect {@code Storm.ServerFps} instead of
 * vanilla's hardcoded 10-TPS scalar. The default method's bytecode lives in the interface class
 * file, and no implementor overrides it ({@code RandAbstract} inherits it; the static {@code
 * Rand.AdjustForFramerate} facade delegates to it), so transforming the interface covers every call
 * path.
 */
public class RandAdjustForFrameratePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.randadjustframerate.";

    public RandAdjustForFrameratePatch() {
        super("zombie.core.random.RandInterface");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "RandAdjustForFramerateAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("AdjustForFramerate")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
