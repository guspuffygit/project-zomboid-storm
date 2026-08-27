package io.pzstorm.storm.advice.basevehicleupdate;

import io.pzstorm.storm.metrics.BaseVehicleUpdateMetrics;
import io.pzstorm.storm.vehicles.StormVehicleSleep;
import net.bytebuddy.asm.Advice;

/**
 * Sleep throttle plus sampled duration metrics for {@code BaseVehicle.update()}.
 *
 * <p>The enter token encodes three outcomes from {@code StormVehicleSleep.enterUpdate}: {@code 0}
 * skips the vanilla body (vehicle asleep this tick, matched by {@code skipOn}), {@code -1} runs it
 * untimed, and a positive {@code nanoTime} runs it and records the duration on exit.
 */
public class BaseVehicleUpdateAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnDefaultValue.class)
    public static long onEnter(@Advice.This Object self) {
        return StormVehicleSleep.enterUpdate(self);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long token) {
        if (token > 0L) {
            BaseVehicleUpdateMetrics.recordNanos(System.nanoTime() - token);
        }
    }
}
