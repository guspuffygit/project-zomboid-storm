package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

public class IsoChunkCheckGrassRegrowthPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isochunkcheckgrassregrowth.";

    public IsoChunkCheckGrassRegrowthPatch() {
        super("zombie.iso.IsoChunk");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "IsoChunkCheckGrassRegrowthAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("CheckGrassRegrowth")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
