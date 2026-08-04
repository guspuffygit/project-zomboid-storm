package io.pzstorm.storm.advice.zombievehicleocclusion;

import io.pzstorm.storm.los.ZombieVehicleOcclusion;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Routes {@code IsoZombie.isVehicleBetween} through {@link ZombieVehicleOcclusion#evaluate}: a
 * non-{@link ZombieVehicleOcclusion#RESULT_VANILLA} verdict skips the vanilla whole-cell vehicle
 * scan and the exit advice writes the verdict as the return value; {@code RESULT_VANILLA} (kill
 * switch, fast-path failure) leaves the vanilla body to run untouched.
 */
public class IsoZombieVehicleBetweenAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.This Object zombie,
            @Advice.Argument(0) float targetX,
            @Advice.Argument(1) float targetY,
            @Advice.Argument(2) float targetZ) {
        if (!GameServer.server) {
            return ZombieVehicleOcclusion.RESULT_VANILLA;
        }
        return ZombieVehicleOcclusion.evaluate(zombie, targetX, targetY, targetZ);
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int verdict, @Advice.Return(readOnly = false) boolean result) {
        if (verdict != ZombieVehicleOcclusion.RESULT_VANILLA) {
            result = verdict == ZombieVehicleOcclusion.RESULT_TRUE;
        }
    }
}
