package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Skips {@code zombie.characters.BodyDamage.setBodyPartsLastState()} when {@code GameServer.server}
 * — see {@link io.pzstorm.storm.advice.bodydamagelaststate.SetBodyPartsLastStateSkipAdvice}. Every
 * caller (the {@code BodyDamage} constructor, {@code IsoPlayer.postupdateInternal}, {@code
 * BodyDamageSync.startSendingUpdates}) writes state that only the client-gated bandage-model
 * updater reads. Server-only by registration gate.
 */
public class BodyDamageLastStateSkipPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.bodydamagelaststate.SetBodyPartsLastStateSkipAdvice";

    public BodyDamageLastStateSkipPatch() {
        super("zombie.characters.BodyDamage.BodyDamage");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("setBodyPartsLastState")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
