package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.lazytiles=true}.
 *
 * <p>Requests every page a newly loaded chunk draws, ahead of its first frame. See {@link
 * io.pzstorm.storm.advice.lazytiles.LazyTilePages}.
 */
public class IsoChunkLazyPageRequestPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.lazytiles.";

    public IsoChunkLazyPageRequestPatch() {
        super("zombie.iso.IsoChunk");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "LazyPageChunkLoadAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("loadInMainThread")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
