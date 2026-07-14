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
 * <p>Skips the synchronized-block entry in {@code
 * AnimationPlayerRecorder.isAnimationRecorderActive(IsoMovingObject)} when the recorder is fully
 * inactive (default in normal gameplay). See {@link
 * io.pzstorm.storm.advice.animationplayerrecorderisactive.AnimationPlayerRecorderIsActiveAdvice}.
 */
public class AnimationPlayerRecorderIsActivePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.animationplayerrecorderisactive.";

    public AnimationPlayerRecorderIsActivePatch() {
        super("zombie.core.skinnedmodel.animation.debug.AnimationPlayerRecorder");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "AnimationPlayerRecorderIsActiveAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("isAnimationRecorderActive")
                                        .and(ElementMatchers.isStatic())
                                        .and(ElementMatchers.takesArguments(1))
                                        .and(ElementMatchers.returns(boolean.class))));
    }
}
