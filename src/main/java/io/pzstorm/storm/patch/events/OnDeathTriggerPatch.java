package io.pzstorm.storm.patch.events;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Applies {@link io.pzstorm.storm.advice.ondeath.OnDeathAdvice} to {@code OnDeath()}. Registered
 * once for {@code IsoGameCharacter} and once for {@code IsoAnimal}, whose override repeats the
 * superclass body instead of calling {@code super.OnDeath()}.
 */
public class OnDeathTriggerPatch extends StormClassTransformer {

    public OnDeathTriggerPatch(String className) {
        super(className);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe("io.pzstorm.storm.advice.ondeath.OnDeathAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("OnDeath")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
