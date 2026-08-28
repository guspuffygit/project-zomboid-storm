package io.pzstorm.storm.advice.vehiclesend;

import io.pzstorm.storm.metrics.MainLoopStepTimings;
import io.pzstorm.storm.metrics.VehicleSendMetrics;
import io.pzstorm.storm.vehicles.StormVehicleSend;
import net.bytebuddy.asm.Advice;

/**
 * Spatially pre-filtered fast path plus duration metrics for {@code
 * VehicleManager.sendVehicles(UdpConnection)}.
 *
 * <p>The enter token encodes three outcomes from {@code StormVehicleSend.enterSendVehicles}: {@code
 * 0} skips the vanilla body (the fast path handled the connection and recorded its own timing,
 * matched by {@code skipOn}), {@code -1} runs it untimed, and a positive {@code nanoTime} runs it
 * and records the duration on exit.
 */
public class VehicleManagerSendVehiclesAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnDefaultValue.class)
    public static long onEnter(@Advice.This Object self, @Advice.Argument(0) Object connection) {
        return StormVehicleSend.enterSendVehicles(self, connection);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long token) {
        if (token > 0L) {
            long elapsed = System.nanoTime() - token;
            VehicleSendMetrics.recordNanos(elapsed);
            MainLoopStepTimings.record("VehicleManager.sendVehicles", elapsed);
        }
    }
}
