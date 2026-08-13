package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}. This is a
 * deliberate, user-approved exception to the no-client-patches rule — do not use it as precedent,
 * and do not register it outside the experimental gate.
 *
 * <p>Caches the name→short-ID resolution for the {@code "doorTrans"} literal inside {@code
 * PropertyContainer.has(String)} — the string overload the {@code FBORenderCell} render-layer
 * helpers hit per door object per dirty frame, which the enum-keyed {@link
 * PropertyContainerHasIdCachePatch} does not cover. See {@link
 * io.pzstorm.storm.advice.propertycontainer.PropertyContainerHasStringIdCacheAdvice} for the
 * rationale and the parity argument (the live container map is still probed every call, so runtime
 * mutation needs no invalidation).
 */
public class PropertyContainerHasStringIdCachePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.propertycontainer.";

    public PropertyContainerHasStringIdCachePatch() {
        super("zombie.core.properties.PropertyContainer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "PropertyContainerHasStringIdCacheAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("has")
                                        .and(ElementMatchers.takesArguments(String.class))));
    }
}
