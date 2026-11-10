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
 * <p>Requests a deferred page when a UI or world draw queues one of its textures. These {@code
 * render} overloads drop an unready texture before it is ever bound, so without this the brush
 * tool's tile picker and moveable tooltips never load a page no chunk has requested. See {@link
 * io.pzstorm.storm.advice.lazytiles.LazyTilePages}.
 */
public class SpriteRenderStateLazyPagePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.lazytiles.";

    public SpriteRenderStateLazyPagePatch() {
        super("zombie.core.sprite.GenericSpriteRenderState");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "LazyPageSpriteStateAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.namedOneOf("render", "renderPoly")
                                        .and(
                                                ElementMatchers.takesArgument(
                                                        0,
                                                        ElementMatchers.named(
                                                                "zombie.core.textures.Texture")))));
    }
}
