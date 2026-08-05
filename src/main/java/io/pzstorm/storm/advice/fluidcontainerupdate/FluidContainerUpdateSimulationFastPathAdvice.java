package io.pzstorm.storm.advice.fluidcontainerupdate;

import io.pzstorm.storm.entity.StormFluidContainerUpdate;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Routes {@code FluidContainerUpdateSystem.updateSimulation()} through {@link
 * StormFluidContainerUpdate#runOptimized}: a {@code true} verdict means the hoisted/reordered pass
 * ran and the vanilla body is skipped; {@code false} (client JVM guard, kill switch, or failure
 * latch) leaves the vanilla body to run untouched.
 *
 * <p>The {@code GameServer.server} guard also subsumes vanilla's {@code !GameClient.client} gate
 * inside the method body — the optimized pass only ever runs where that gate is known true.
 */
public class FluidContainerUpdateSimulationFastPathAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object system) {
        if (!GameServer.server) {
            return false;
        }
        return StormFluidContainerUpdate.runOptimized(system);
    }
}
