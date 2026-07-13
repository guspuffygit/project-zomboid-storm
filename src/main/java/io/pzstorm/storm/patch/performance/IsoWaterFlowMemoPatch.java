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
 * <p>Memoizes {@code IsoWaterFlow.getFlow} per world position with a TTL, and clears the memo on
 * {@code Reset()}. See {@link io.pzstorm.storm.advice.waterflow.WaterFlowGetFlowMemoAdvice} for the
 * rationale and profiling data.
 */
public class IsoWaterFlowMemoPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.waterflow.";

    public IsoWaterFlowMemoPatch() {
        super("zombie.iso.IsoWaterFlow");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "WaterFlowGetFlowMemoAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("getFlow")
                                                .and(ElementMatchers.takesArguments(4))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "WaterFlowResetAdvice").resolve(),
                                        locator)
                                .on(ElementMatchers.named("Reset")));
    }
}
