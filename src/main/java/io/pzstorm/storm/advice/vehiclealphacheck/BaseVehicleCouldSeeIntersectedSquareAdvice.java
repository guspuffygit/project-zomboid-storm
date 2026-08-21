package io.pzstorm.storm.advice.vehiclealphacheck;

import io.pzstorm.storm.vehicles.StormVehicleAlphaCheckSkip;
import net.bytebuddy.asm.Advice;

/**
 * Skips {@code BaseVehicle.couldSeeIntersectedSquare(int)} on the dedicated server — see {@link
 * StormVehicleAlphaCheckSkip}. Returning {@code true} makes Byte Buddy skip the body; the method
 * then returns its default {@code false}.
 */
public final class BaseVehicleCouldSeeIntersectedSquareAdvice {

    private BaseVehicleCouldSeeIntersectedSquareAdvice() {}

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return StormVehicleAlphaCheckSkip.shouldSkip();
    }
}
