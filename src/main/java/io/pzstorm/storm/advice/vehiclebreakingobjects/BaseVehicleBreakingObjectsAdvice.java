package io.pzstorm.storm.advice.vehiclebreakingobjects;

import io.pzstorm.storm.vehicles.StormVehicleBreakingObjectsSkip;
import net.bytebuddy.asm.Advice;

/**
 * Skips {@code BaseVehicle.breakingObjects()} for driverless vehicles on the dedicated server — see
 * {@link StormVehicleBreakingObjectsSkip}.
 */
public class BaseVehicleBreakingObjectsAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object self) {
        return StormVehicleBreakingObjectsSkip.shouldSkip(self);
    }
}
