package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only hook on {@code Core.ResetLua(String, String)} for the connect-time fast path (see
 * {@code io.pzstorm.storm.client.StormFastResetLua}). The advice offers every ResetLua call to the
 * fast path; a {@code true} return means the lite pass already ran and vanilla's body is skipped,
 * {@code false} runs vanilla untouched. The deprecated {@code ResetLua(boolean, String)} overload
 * delegates to this one, so a single advice sees every reset.
 */
public class CoreResetLuaPatch extends StormClassTransformer {

    public CoreResetLuaPatch() {
        super("zombie.core.Core");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(ResetLuaAdvice.class)
                        .on(
                                ElementMatchers.named("ResetLua")
                                        .and(
                                                ElementMatchers.takesArguments(
                                                        String.class, String.class))));
    }

    public static class ResetLuaAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter(
                @Advice.Argument(0) String activeMods, @Advice.Argument(1) String reason) {
            return io.pzstorm.storm.client.StormFastResetLua.tryFastPath(activeMods, reason);
        }
    }
}
