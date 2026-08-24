package io.pzstorm.storm.advice.vehiclecropcheck;

import io.pzstorm.storm.vehicles.StormVehicleCropCheckSkip;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Routes {@code BaseVehicle.notKillCrops()} through {@link StormVehicleCropCheckSkip#shouldSkip}:
 * when the vehicle has no driver and is not being towed, the vanilla body is skipped and the exit
 * advice forces the return value to {@code true} ("does not kill crops"), short-circuiting the
 * crop-crushing block in {@code BaseVehicle.update()}. Otherwise the vanilla body runs untouched.
 */
public class BaseVehicleNotKillCropsAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object vehicle) {
        if (!GameServer.server) {
            return false;
        }
        return StormVehicleCropCheckSkip.shouldSkip(vehicle);
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter boolean skipped, @Advice.Return(readOnly = false) boolean result) {
        if (skipped) {
            result = true;
        }
    }
}
