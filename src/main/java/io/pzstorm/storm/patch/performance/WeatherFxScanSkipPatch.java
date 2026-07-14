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
 * <p>Skips {@code WeatherFxMask.scanForTiles(int)} when {@code requiresUpdate} is false. See {@link
 * io.pzstorm.storm.advice.weatherfxmask.WeatherFxScanSkipAdvice} for the rationale.
 */
public class WeatherFxScanSkipPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.weatherfxmask.";

    public WeatherFxScanSkipPatch() {
        super("zombie.iso.weather.fx.WeatherFxMask");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "WeatherFxScanSkipAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("scanForTiles")
                                        .and(ElementMatchers.isStatic())
                                        .and(ElementMatchers.takesArguments(int.class))));
    }
}
