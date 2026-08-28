package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Skips {@code BaseVehicle.breakingObjects()} on the dedicated server for vehicles whose tow chain
 * has no driver — see {@link io.pzstorm.storm.vehicles.StormVehicleBreakingObjectsSkip}. Profiled
 * at ~1.9 ms/tick on a 1,792-vehicle server (scan #5). Deliberate behavior change: driverless
 * vehicles no longer break/slow world objects on the server.
 *
 * <p>Re-validate on every game update: the skip assumes {@code breakingObjects()} stays a per-tick
 * call from {@code BaseVehicle.update()} whose state ({@code breakingObjectsList}, {@code
 * breakingSlowFactor}) is fully rebuilt by the next executed scan.
 */
public class BaseVehicleBreakingObjectsSkipPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.vehicles.BaseVehicle";
    private static final String METHOD = "breakingObjects";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.vehiclebreakingobjects.BaseVehicleBreakingObjectsAdvice";

    public BaseVehicleBreakingObjectsSkipPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods().filter(ElementMatchers.named(METHOD)).isEmpty()) {
            throw new IllegalStateException(
                    "BaseVehicleBreakingObjectsSkipPatch: BaseVehicle no longer declares "
                            + METHOD
                            + " — the name-string hook would silently no-op and reintroduce the"
                            + " per-vehicle square walk. Re-verify the patch against the current"
                            + " game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(ElementMatchers.named(METHOD).and(ElementMatchers.takesArguments(0))));
    }
}
