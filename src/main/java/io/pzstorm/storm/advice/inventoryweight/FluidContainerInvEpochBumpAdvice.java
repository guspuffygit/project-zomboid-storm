package io.pzstorm.storm.advice.inventoryweight;

import io.pzstorm.storm.inventory.StormInventoryWeight;
import net.bytebuddy.asm.Advice;

/**
 * Exit advice (including exceptional exit) for every {@code FluidContainer} method that changes the
 * stored amount &mdash; routes to the character holding the container's owning item, if any.
 * Applied by {@code FluidContainerInvEpochPatch}.
 */
public class FluidContainerInvEpochBumpAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.This Object self) {
        StormInventoryWeight.bumpFluidContainer(self);
    }
}
