package io.pzstorm.storm.patch.rendering;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Part of the launcher's "Skip menus" toggle ({@code -Dstorm.skipmenus=true}): jumps the logo state
 * straight to its exit stage so no TIS/attribution/Storm logo screens play. Registered only when
 * the property is set — see {@code StormClassTransformers}.
 */
public class TISLogoStateSkipPatch extends StormClassTransformer {

    public TISLogoStateSkipPatch() {
        super("zombie.gameStates.TISLogoState");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(Advice.to(EnterAdvice.class).on(ElementMatchers.named("enter")));
    }

    public static class EnterAdvice {
        @Advice.OnMethodExit
        public static void onExit(
                @Advice.FieldValue(value = "stage", readOnly = false) int stage,
                @Advice.FieldValue(value = "alpha", readOnly = false) float alpha,
                @Advice.FieldValue(value = "targetAlpha", readOnly = false) float targetAlpha) {
            // stage 3 with alpha already at 0 makes the first update() return Continue
            stage = 3;
            alpha = 0.0F;
            targetAlpha = 0.0F;
        }
    }
}
