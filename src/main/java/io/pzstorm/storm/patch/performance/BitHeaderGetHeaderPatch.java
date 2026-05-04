package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Instruments {@code BitHeader.getHeader(HeaderSize, ByteBuffer, boolean)} so {@link
 * io.pzstorm.storm.metrics.BitHeaderMetrics} can count the demand rate per {@code HeaderSize}.
 *
 * <p>Loading this class also drags in {@code BitHeaderMetrics}, whose static initializer starts its
 * 60s reporter daemon and triggers {@link io.pzstorm.storm.metrics.ThreadAllocBytesMetrics}.
 */
public class BitHeaderGetHeaderPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.bitheader.";

    public BitHeaderGetHeaderPatch() {
        super("zombie.util.io.BitHeader");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "BitHeaderGetHeaderAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("getHeader")
                                        .and(ElementMatchers.takesArguments(3))
                                        .and(ElementMatchers.isStatic())));
    }
}
