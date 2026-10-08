package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.logging.StormLogger;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only, opt-in through {@code -Dstorm.experimental.clientperf=true}. Resolving the head and
 * feet for each drawn character's shadow performs two hash probes for every existing bone in
 * 42.21.0. A nonnull live value from a plain HashMap needs one probe; all other cases keep the
 * native body, including its null-value unboxing exception and caller-supplied default.
 *
 * <p>No cache or injected state: changes to the map, its values, or the player's SkinningData are
 * visible on the next call. Java instrumentation is needed because the duplicate probes are inside
 * AnimationPlayer's native Java method; server and Lua changes cannot remove them. Transformation
 * and advice failures keep vanilla. Revalidate the native getter and lookup bodies on game updates.
 */
public class AnimationPlayerBoneIndexLookupPatch extends StormClassTransformer {
    private static final String TARGET = "zombie.core.skinnedmodel.animation.AnimationPlayer";
    private static final String DATA = "zombie.core.skinnedmodel.model.SkinningData";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.animationboneindex.AnimationBoneIndexAdvice";

    public AnimationPlayerBoneIndexLookupPatch() {
        super(TARGET);
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        try {
            return super.transform(rawClass);
        } catch (Throwable failure) {
            StormLogger.LOGGER.warn(
                    "AnimationPlayer bone-index fast path unavailable; keeping native lookup",
                    failure);
            return rawClass;
        }
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        ElementMatcher.Junction<MethodDescription> method =
                ElementMatchers.named("getSkinningBoneIndex")
                        .and(ElementMatchers.takesArguments(String.class, int.class))
                        .and(ElementMatchers.returns(int.class))
                        .and(ElementMatchers.not(ElementMatchers.isStatic()));
        if (target.getDeclaredMethods().filter(method).size() != 1
                || target.getDeclaredFields()
                                .filter(
                                        ElementMatchers.named("skinningData")
                                                .and(
                                                        ElementMatchers.fieldType(
                                                                typePool.describe(DATA).resolve()))
                                                .and(
                                                        ElementMatchers.not(
                                                                ElementMatchers.isStatic())))
                                .size()
                        != 1) {
            throw new IllegalStateException(
                    "AnimationPlayer bone-index method or skinningData field changed; revalidate against native source");
        }
        return builder.visit(Advice.to(typePool.describe(ADVICE).resolve(), locator).on(method));
    }
}
