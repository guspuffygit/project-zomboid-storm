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
 * <p>Requests a deferred page when a drawer hands its id to {@code VBORenderer} without going
 * through {@code Texture.bind}. Object outlines, clock hands and world items draw this way. See
 * {@link io.pzstorm.storm.advice.lazytiles.LazyTilePages}.
 */
public class VBORendererLazyPageTexturePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.lazytiles.";

    public VBORendererLazyPageTexturePatch() {
        super("zombie.core.opengl.VBORenderer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "LazyPageVboTextureAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("setTextureID")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
