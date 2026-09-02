package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Skips {@code IsoGameCharacter.updateEmitter()} for animals on the dedicated server, where the
 * sound emitter is a dummy and the FMOD parameter recompute (notably the uncached server-side
 * {@code getPuddlesInGround}) is pure waste. See {@link
 * io.pzstorm.storm.advice.isogamecharacterupdateemitter.IsoGameCharacterUpdateEmitterAdvice}.
 *
 * <p>Server-only. Mirrors the guard vanilla already applies to players in {@code
 * IsoPlayer.updateInternal1}.
 */
public class IsoGameCharacterUpdateEmitterServerSkipPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isogamecharacterupdateemitter.";

    public IsoGameCharacterUpdateEmitterServerSkipPatch() {
        super("zombie.characters.IsoGameCharacter");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "IsoGameCharacterUpdateEmitterAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("updateEmitter")
                                        .and(ElementMatchers.takesArguments(0))
                                        .and(ElementMatchers.returns(void.class))));
    }
}
