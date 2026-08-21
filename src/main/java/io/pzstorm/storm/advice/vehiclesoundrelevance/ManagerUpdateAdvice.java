package io.pzstorm.storm.advice.vehiclesoundrelevance;

import io.pzstorm.storm.vehicles.StormVehicleSoundRelevance;
import net.bytebuddy.asm.Advice;

/**
 * Brackets {@code zombie.vehicleNetworkSound.server.Manager.update()} with the per-tick noisy
 * vehicle snapshot — see {@link StormVehicleSoundRelevance}.
 */
public final class ManagerUpdateAdvice {

    private ManagerUpdateAdvice() {}

    @Advice.OnMethodEnter
    public static void onEnter() {
        StormVehicleSoundRelevance.beginTick();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        StormVehicleSoundRelevance.endTick();
    }
}
