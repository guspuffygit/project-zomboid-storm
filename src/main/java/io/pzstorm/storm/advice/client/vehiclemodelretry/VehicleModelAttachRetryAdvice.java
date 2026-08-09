package io.pzstorm.storm.advice.client.vehiclemodelretry;

import net.bytebuddy.asm.Advice;
import zombie.network.GameClient;
import zombie.vehicles.BaseVehicle;

/**
 * Exit hook on {@code BaseVehicle.update()}. The inline gate keeps the common case (model attached)
 * to two field reads per vehicle per tick; the throttled retry logic lives in {@link
 * VehicleModelAttachRetry}.
 */
public class VehicleModelAttachRetryAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.This BaseVehicle vehicle) {
        if (!GameClient.client) {
            return;
        }
        if (vehicle.sprite == null || vehicle.sprite.modelSlot != null) {
            return;
        }
        VehicleModelAttachRetry.retryIfModelMissing(vehicle);
    }
}
