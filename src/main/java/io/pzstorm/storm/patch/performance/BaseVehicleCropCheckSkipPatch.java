package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Short-circuits {@code BaseVehicle.notKillCrops()} to {@code true} for vehicles that have no
 * driver and are not being towed (see {@code StormVehicleCropCheckSkip}). The crop-crushing block
 * in {@code BaseVehicle.update()} is guarded by {@code !notKillCrops()}, so this skips its per-tick
 * cost — ~17 redundant {@code ServerMap.getGridSquare} calls plus the {@code
 * GlobalObjectLookup}/{@code SGlobalObjects.getSystemByName("farming")} linear scans — for every
 * parked vehicle. Server-only by registration gate.
 *
 * <p>Re-validate on game update: the hook is the name string {@code notKillCrops} and the
 * assumption that {@code update()}'s crop block is gated on it (BaseVehicle.java:3500 in 42.20.3).
 */
public class BaseVehicleCropCheckSkipPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.vehicles.BaseVehicle";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.vehiclecropcheck.BaseVehicleNotKillCropsAdvice";

    public BaseVehicleCropCheckSkipPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods().filter(ElementMatchers.named("notKillCrops")).size() != 1) {
            throw new IllegalStateException(
                    "BaseVehicleCropCheckSkipPatch: BaseVehicle no longer declares exactly one"
                            + " notKillCrops() — the name-string hook would silently no-op (or hit"
                            + " the wrong overload) and reintroduce the per-tick crop-crushing"
                            + " scan for parked vehicles. Re-verify the patch against the current"
                            + " game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(ElementMatchers.named("notKillCrops")));
    }
}
