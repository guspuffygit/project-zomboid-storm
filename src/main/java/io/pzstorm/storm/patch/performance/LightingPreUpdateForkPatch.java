package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}. This is a
 * deliberate, user-approved exception to the no-client-patches rule — do not use it as precedent,
 * and do not register it outside the experimental gate.
 *
 * <p>Forces {@code LightingJNI.preUpdate()} to always fork {@code checkLights} to {@code
 * zombie.core.PZForkJoinPool} regardless of the {@code DebugOptions.threadLighting} flag. See
 * {@link io.pzstorm.storm.advice.lightingpreupdatefork.LightingPreUpdateForkAdvice} for the
 * mechanism and profiling data.
 */
public class LightingPreUpdateForkPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.lightingpreupdatefork.";

    public LightingPreUpdateForkPatch() {
        super("zombie.iso.LightingJNI");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "LightingPreUpdateForkAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("preUpdate")
                                        .and(ElementMatchers.isStatic())
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
