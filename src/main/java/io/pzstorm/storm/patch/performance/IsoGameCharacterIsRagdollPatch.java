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
 * <p>Bypasses the model-swap side effect of {@code getAnimationPlayer()} inside {@code
 * IsoGameCharacter.isRagdoll()}. See {@link
 * io.pzstorm.storm.advice.isogamecharacterisragdoll.IsoGameCharacterIsRagdollAdvice} for the
 * mechanism and profiling data.
 */
public class IsoGameCharacterIsRagdollPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isogamecharacterisragdoll.";

    public IsoGameCharacterIsRagdollPatch() {
        super("zombie.characters.IsoGameCharacter");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "IsoGameCharacterIsRagdollAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("isRagdoll")
                                        .and(ElementMatchers.takesArguments(0))
                                        .and(ElementMatchers.returns(boolean.class))));
    }
}
