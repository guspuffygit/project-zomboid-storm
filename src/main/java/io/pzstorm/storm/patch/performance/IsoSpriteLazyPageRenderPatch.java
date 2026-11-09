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
 * <p>Skips drawing a tile whose page is still loading and requests the page. See {@link
 * io.pzstorm.storm.advice.lazytiles.LazyTilePages}.
 */
public class IsoSpriteLazyPageRenderPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.lazytiles.";

    public IsoSpriteLazyPageRenderPatch() {
        super("zombie.iso.sprite.IsoSprite");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "LazyPageRenderFrameAdvice").resolve(), locator)
                        .on(ElementMatchers.named("performRenderFrame")));
    }
}
