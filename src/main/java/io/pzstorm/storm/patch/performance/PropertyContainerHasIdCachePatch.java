package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.properties.IsoPropertyType;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}. This is a
 * deliberate, user-approved exception to the no-client-patches rule — do not use it as precedent,
 * and do not register it outside the experimental gate.
 *
 * <p>Caches the enum→short-ID resolution inside {@code PropertyContainer.has(IsoPropertyType)} so
 * per-call {@code getIDFromPropertyName(String)} HashMap lookups become a single array read. See
 * {@link io.pzstorm.storm.advice.propertycontainer.PropertyContainerHasIdCacheAdvice} for the
 * rationale and profiling data.
 */
public class PropertyContainerHasIdCachePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.propertycontainer.";

    public PropertyContainerHasIdCachePatch() {
        super("zombie.core.properties.PropertyContainer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "PropertyContainerHasIdCacheAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("has")
                                        .and(
                                                ElementMatchers.takesArguments(
                                                        IsoPropertyType.class))));
    }
}
