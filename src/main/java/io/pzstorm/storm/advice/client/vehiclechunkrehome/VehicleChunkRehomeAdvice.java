package io.pzstorm.storm.advice.client.vehiclechunkrehome;

import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * Enter hook on {@code BaseVehicle.update()} — runs before vanilla's {@code chunk.refs.isEmpty()}
 * check so a vehicle stranded on an unloaded chunk with a local player aboard is re-homed instead
 * of frozen. See {@link VehicleChunkRehome}.
 */
public class VehicleChunkRehomeAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This BaseVehicle vehicle) {
        if (GameServer.server) {
            return;
        }
        VehicleChunkRehome.beforeUpdate(vehicle);
    }
}
