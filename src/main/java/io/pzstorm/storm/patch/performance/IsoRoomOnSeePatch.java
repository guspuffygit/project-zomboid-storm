package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Server-only patch that wraps {@code IsoRoom.onSee} in the parallel-LOS lock (no-op below 2
 * workers). MUST be registration-gated to the dedicated server ({@code StormEnv.isStormServer()}) —
 * {@code IsoRoom} runs on the client and the HARD RULE forbids transforming client bytecode.
 */
public class IsoRoomOnSeePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isoroomonsee.";

    public IsoRoomOnSeePatch() {
        super("zombie.iso.areas.IsoRoom");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "IsoRoomOnSeeAdvice").resolve(), locator)
                        .on(ElementMatchers.named("onSee")));
    }
}
