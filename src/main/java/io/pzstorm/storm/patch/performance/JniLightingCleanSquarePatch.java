package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}. This is a
 * deliberate, user-approved exception to the no-client-patches rule — do not use it as precedent,
 * and do not register it outside the experimental gate.
 *
 * <p>Skips the per-frame {@code getSquareDirty} JNI re-cross for squares already verified clean in
 * the current lighting pass. See {@link
 * io.pzstorm.storm.advice.jnilighting.JniLightingCleanSkipAdvice} for the mechanism and profiling
 * data. Defines {@code storm$cleanCounter} on the class to hold the per-instance clean verdict.
 */
public class JniLightingCleanSquarePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.jnilighting.";

    public JniLightingCleanSquarePatch() {
        super("zombie.iso.LightingJNI$JNILighting");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField("storm$cleanCounter", int.class, Visibility.PUBLIC)
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "JniLightingCleanSkipAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("update")
                                                .and(ElementMatchers.takesArguments(0))));
    }
}
