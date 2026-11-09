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
 * <p>Requests a deferred page when the render thread binds one of its textures. See {@link
 * io.pzstorm.storm.advice.lazytiles.LazyTilePages}.
 */
public class TextureLazyPageBindPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.lazytiles.";

    public TextureLazyPageBindPatch() {
        super("zombie.core.textures.Texture");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "LazyPageBindAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("bind")
                                        .and(ElementMatchers.takesArguments(int.class))));
    }
}
