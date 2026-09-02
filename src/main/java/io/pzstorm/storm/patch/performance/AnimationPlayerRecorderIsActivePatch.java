package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Short-circuits {@code AnimationPlayerRecorder.isAnimationRecorderActive(IsoMovingObject)} when
 * the debug animation recorder is fully inactive — the default in normal gameplay on both the
 * client and the dedicated server. See {@link
 * io.pzstorm.storm.advice.animationplayerrecorderisactive.AnimationPlayerRecorderIsActiveAdvice}.
 *
 * <p>Registered on the server unconditionally and on the client behind {@code
 * -Dstorm.experimental.clientperf=true}. The check is reached per moving object per tick from
 * {@code IsoMovingObject.updateAnimationRecorder()} and per vehicle from {@code
 * BaseVehicle.update}; with the recorder off, vanilla still walks every player slot before
 * concluding "no".
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
