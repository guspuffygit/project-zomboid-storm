package io.pzstorm.storm.patch.rendering;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

public class EpilepsyWarningSkipPatch extends StormClassTransformer {

    public EpilepsyWarningSkipPatch() {
        super("zombie.GameWindow");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(SkipAdvice.class).on(ElementMatchers.named("doEpilepsyWarningText")));
    }

    public static class SkipAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter() {
            return true;
        }
    }
}
