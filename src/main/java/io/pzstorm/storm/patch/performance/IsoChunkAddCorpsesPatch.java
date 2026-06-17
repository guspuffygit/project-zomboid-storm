package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

public class IsoChunkAddCorpsesPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isochunkaddcorpses.";

    public IsoChunkAddCorpsesPatch() {
        super("zombie.iso.IsoChunk");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "IsoChunkAddCorpsesAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("AddCorpses")
                                        .and(ElementMatchers.takesArguments(2))));
    }
}
