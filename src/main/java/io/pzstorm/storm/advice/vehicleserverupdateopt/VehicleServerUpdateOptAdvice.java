package io.pzstorm.storm.advice.vehicleserverupdateopt;

import io.pzstorm.storm.patch.performance.StormVehicleServerUpdate;
import net.bytebuddy.asm.Advice;

/**
 * Replaces {@code VehicleManager.serverUpdate()} with {@link
 * StormVehicleServerUpdate#runOptimizedOrSkip()} on the dedicated server. When the helper returns
 * {@code true} the vanilla body (including the inner timing advice from {@code
 * VehicleManagerServerUpdatePatch}, registered earlier) is skipped; the helper records the
 * "VehicleManager.serverUpdate" timing step itself in that case, so the two recorders are never
 * live for the same call.
 */
public class VehicleServerUpdateOptAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return StormVehicleServerUpdate.runOptimizedOrSkip();
    }
}
