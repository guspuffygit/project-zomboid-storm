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
 * <p>Caches {@code IsoLightSwitch.hasElectricityAround()} per switch with a short TTL. See {@link
 * io.pzstorm.storm.advice.lightswitchelectricity.LightSwitchElectricityMemoAdvice} for the
 * rationale, profiling data, and staleness bound.
 */
public class IsoLightSwitchElectricityMemoPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.lightswitchelectricity.";

    public IsoLightSwitchElectricityMemoPatch() {
        super("zombie.iso.objects.IsoLightSwitch");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "LightSwitchElectricityMemoAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("hasElectricityAround")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
