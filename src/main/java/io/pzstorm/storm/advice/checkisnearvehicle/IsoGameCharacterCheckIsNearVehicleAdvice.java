package io.pzstorm.storm.advice.checkisnearvehicle;

import io.pzstorm.storm.vehicles.StormCheckIsNearVehicle;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Routes {@code IsoGameCharacter.checkIsNearVehicle} through {@link
 * StormCheckIsNearVehicle#evaluate}: a non-{@link StormCheckIsNearVehicle#RESULT_VANILLA} verdict
 * skips the vanilla whole-cell vehicle scan and the exit advice writes the verdict as the return
 * value; {@code RESULT_VANILLA} (kill switch, fast-path failure) leaves the vanilla body to run
 * untouched.
 */
public class IsoGameCharacterCheckIsNearVehicleAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(@Advice.This Object character) {
        if (!GameServer.server) {
            return StormCheckIsNearVehicle.RESULT_VANILLA;
        }
        return StormCheckIsNearVehicle.evaluate(character);
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int verdict, @Advice.Return(readOnly = false) boolean result) {
        if (verdict != StormCheckIsNearVehicle.RESULT_VANILLA) {
            result = verdict == StormCheckIsNearVehicle.RESULT_TRUE;
        }
    }
}
