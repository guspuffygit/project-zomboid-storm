package io.pzstorm.storm.advice.vehiclesend;

import io.pzstorm.storm.metrics.VehicleSendMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class VehicleManagerSendVehiclesAdvice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        if (!GameServer.server) {
            return 0L;
        }
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long startNanos) {
        if (!GameServer.server) {
            return;
        }
        if (startNanos == 0L) {
            return;
        }
        VehicleSendMetrics.recordNanos(System.nanoTime() - startNanos);
    }
}
