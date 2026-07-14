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
 * <p>Mirrors of {@link IsoGameCharacterIsRagdollPatch} for the two sibling ragdoll query methods:
 * {@code isFullyRagdolling()} and {@code isRagdollSimulationActive()}. Both share the same {@code
 * hasAnimationPlayer() && getAnimationPlayer().X()} shape and pay the same {@code
 * getAnimationPlayer} model-swap cost. See the individual advice classes for details.
 */
public class IsoGameCharacterRagdollMirrorsPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isogamecharacterragdollmirrors.";

    public IsoGameCharacterRagdollMirrorsPatch() {
        super("zombie.characters.IsoGameCharacter");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "IsFullyRagdollingAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("isFullyRagdolling")
                                                .and(ElementMatchers.takesArguments(0))
                                                .and(ElementMatchers.returns(boolean.class))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "IsRagdollSimulationActiveAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("isRagdollSimulationActive")
                                                .and(ElementMatchers.takesArguments(0))
                                                .and(ElementMatchers.returns(boolean.class))));
    }
}
