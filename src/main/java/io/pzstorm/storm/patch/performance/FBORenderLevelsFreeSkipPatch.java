package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}. This is a
 * deliberate, user-approved exception to the no-client-patches rule — do not use it as precedent,
 * and do not register it outside the experimental gate.
 *
 * <p>Skips the per-frame per-level {@code freeFBOsForLevel} no-op churn for chunks that hold no
 * render chunks. Defines {@code storm$noFBOs} on the class; see {@link
 * io.pzstorm.storm.advice.fborenderlevels.FreeFBOsForLevelSkipAdvice} for the flag lifecycle and
 * safety argument.
 */
public class FBORenderLevelsFreeSkipPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.fborenderlevels.";

    public FBORenderLevelsFreeSkipPatch() {
        super("zombie.iso.fboRenderChunk.FBORenderLevels");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField("storm$noFBOs", boolean.class, Visibility.PUBLIC)
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "FreeFBOsForLevelSkipAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("freeFBOsForLevel")
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "CreateFBOFlagClearAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("createFBOForLevel")
                                                .and(ElementMatchers.takesArguments(2))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "ClearCacheFlagSetAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("clearCache")
                                                .and(ElementMatchers.takesArguments(0))));
    }
}
