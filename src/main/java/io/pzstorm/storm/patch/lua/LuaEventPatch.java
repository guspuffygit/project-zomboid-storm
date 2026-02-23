package io.pzstorm.storm.patch.lua;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

public class LuaEventPatch extends StormClassTransformer {

    public LuaEventPatch() {
        super("zombie.Lua.Event");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe("io.pzstorm.storm.advice.TriggerLuaEventAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("trigger")
                                        .and(ElementMatchers.takesArguments(3))));
    }
}
