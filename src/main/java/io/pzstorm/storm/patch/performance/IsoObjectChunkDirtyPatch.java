package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

public class IsoObjectChunkDirtyPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.chunkdirty.";

    public IsoObjectChunkDirtyPatch() {
        super("zombie.iso.IsoObject");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "IsoObjectMarkChunkDirtyAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("addToWorld")
                                        .or(ElementMatchers.named("setSprite"))
                                        .or(ElementMatchers.named("setSpriteFromName"))
                                        .or(ElementMatchers.named("setName"))
                                        .or(ElementMatchers.named("setRenderYOffset"))
                                        .or(ElementMatchers.named("setCustomColor"))
                                        .or(ElementMatchers.named("setOverlaySprite"))
                                        .or(ElementMatchers.named("setOverlaySpriteColor"))
                                        .or(ElementMatchers.named("setKeyId"))
                                        .or(ElementMatchers.named("setMovedThumpable"))
                                        .or(ElementMatchers.named("setSpriteModelName"))));
    }
}
