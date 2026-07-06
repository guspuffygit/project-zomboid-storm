package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Skips the wasteful parts of {@code VehicleManager.serverUpdate()}: the 512-slot scan that
 * iterates the whole vehicle set for never-connected slots, and the redundant second call per
 * frame. See {@link StormVehicleServerUpdate} for the invariant analysis and kill switch.
 *
 * <p>Must be registered <em>after</em> {@code VehicleManagerServerUpdatePatch} so this advice wraps
 * outside the timing advice.
 */
public class VehicleManagerServerUpdateOptPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.vehicleserverupdateopt.";

    public VehicleManagerServerUpdateOptPatch() {
        super("zombie.vehicles.VehicleManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "VehicleServerUpdateOptAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("serverUpdate")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
