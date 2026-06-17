package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

public class GlobalModDataSavePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.globalmoddatasave.";

    public GlobalModDataSavePatch() {
        super("zombie.world.moddata.GlobalModData");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "GlobalModDataSaveAdvice").resolve(), locator)
                        .on(ElementMatchers.named("save").and(ElementMatchers.takesArguments(0))));
    }
}
