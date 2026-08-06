package io.pzstorm.storm.patch.rendering;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Part of the launcher's "Skip menus" toggle ({@code -Dstorm.skipmenus=true}): suppresses the
 * terms-of-service state. The whole {@code enter()} body is skipped — not just fast-exited —
 * because it fires OnGameStateEnter, whose Lua handler would put the TOS modal on the main menu
 * with nothing left to dismiss it. Registered only when the property is set — see {@code
 * StormClassTransformers}.
 */
public class TermsOfServiceStateSkipPatch extends StormClassTransformer {

    public TermsOfServiceStateSkipPatch() {
        super("zombie.gameStates.TermsOfServiceState");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(Advice.to(EnterAdvice.class).on(ElementMatchers.named("enter")));
    }

    public static class EnterAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter() {
            return true;
        }

        @Advice.OnMethodExit
        public static void onExit(
                @Advice.FieldValue(value = "exit", readOnly = false) boolean exit) {
            exit = true;
        }
    }
}
