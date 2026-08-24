package io.pzstorm.storm.advice.client.vehicletowsnap;

import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * Enter hook on the 5-arg {@code BaseVehicle.addPointConstraint} — the single funnel every tow
 * constraint creation goes through on a client JVM (player attach, chunk-reload reconnect,
 * seat-change rebuild, {@code VehicleTowingAttachPacket}). Runs before vanilla breaks the old
 * constraint and creates the new one, so the snap happens while no constraint is live and before
 * any physics step sees the corrected positions.
 */
public class VehicleTowConstraintSnapAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.This BaseVehicle vehicleA,
            @Advice.Argument(1) BaseVehicle vehicleB,
            @Advice.Argument(2) String attachmentA,
            @Advice.Argument(3) String attachmentB) {
        if (GameServer.server) {
            return;
        }
        VehicleTowConstraintSnap.beforeAttach(vehicleA, vehicleB, attachmentA, attachmentB);
    }
}
