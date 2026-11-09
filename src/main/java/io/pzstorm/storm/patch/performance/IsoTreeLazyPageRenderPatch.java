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
 * <p>Skips a tree whose pages are still loading and requests them, including the jumbo trunk and
 * treetop sprites a cut-away tree draws. See {@link
 * io.pzstorm.storm.advice.lazytiles.LazyTilePages#skipTree}.
 */
public class IsoTreeLazyPageRenderPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.lazytiles.";

    public IsoTreeLazyPageRenderPatch() {
        super("zombie.iso.objects.IsoTree");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "LazyPageTreeRenderAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("render")
                                        .and(ElementMatchers.isPrivate())
                                        .and(ElementMatchers.takesArguments(7))
                                        .and(ElementMatchers.takesArgument(5, int.class))));
    }
}
