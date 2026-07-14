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
 * <p>Caches the {@code name.isBlank()} verdict and inlines the entire fast path of {@code
 * AnimationVariableReference.getVariable(IAnimationVariableSource)}. See {@link
 * io.pzstorm.storm.advice.animationvariablereferencegetvariable.AnimationVariableReferenceGetVariableAdvice}
 * for the mechanism and profiling data.
 *
 * <p>Defines {@code storm$notBlank} on the class to hold the confirmed-not-blank verdict.
 */
public class AnimationVariableReferenceGetVariablePatch extends StormClassTransformer {

    private static final String PKG =
            "io.pzstorm.storm.advice.animationvariablereferencegetvariable.";

    public AnimationVariableReferenceGetVariablePatch() {
        super("zombie.core.skinnedmodel.advancedanimation.AnimationVariableReference");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField("storm$notBlank", boolean.class, Visibility.PUBLIC)
                .visit(
                        Advice.to(
                                        typePool.describe(
                                                        PKG
                                                                + "AnimationVariableReferenceGetVariableAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("getVariable")
                                                .and(ElementMatchers.takesArguments(1))
                                                .and(
                                                        ElementMatchers.returns(
                                                                typePool.describe(
                                                                                "zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSlot")
                                                                        .resolve()))));
    }
}
