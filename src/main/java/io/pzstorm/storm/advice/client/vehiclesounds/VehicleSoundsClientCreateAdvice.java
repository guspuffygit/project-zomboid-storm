package io.pzstorm.storm.advice.client.vehiclesounds;

import net.bytebuddy.asm.Advice;
import zombie.vehicles.BaseVehicle;

/**
 * Enter hook on {@code BaseVehicle.checkVehicleSoundsExists()} — makes the method live up to its
 * name on multiplayer clients. See {@link VehicleSoundsClientCreate}.
 */
public class VehicleSoundsClientCreateAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This BaseVehicle vehicle) {
        VehicleSoundsClientCreate.ensureExists(vehicle);
    }
}
