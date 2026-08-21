package io.pzstorm.storm.advice.vehiclesoundrelevance;

import io.pzstorm.storm.vehicles.StormVehicleSoundRelevance;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import zombie.vehicles.BaseVehicle;

/**
 * Replaces {@code Manager.getVehiclesRelevantToConnection(Connection, Set)} with the snapshot
 * answer from {@link StormVehicleSoundRelevance#fill}; returning {@code false} runs the vanilla
 * body untouched.
 */
public final class ManagerGetVehiclesRelevantAdvice {

    private ManagerGetVehiclesRelevantAdvice() {}

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.Argument(0) Object connection, @Advice.Argument(1) Set<BaseVehicle> vehicles) {
        return StormVehicleSoundRelevance.fill(connection, vehicles);
    }
}
