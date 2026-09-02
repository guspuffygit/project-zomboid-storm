package io.pzstorm.storm.advice.inventoryweight;

import io.pzstorm.storm.inventory.StormInventoryWeight;
import net.bytebuddy.asm.Advice;

/**
 * Exit advice (including exceptional exit) for every {@code WornItems} method that mutates its item
 * list &mdash; routes to the owning character via {@code StormWornItemsOwnerHolder}. Applied by
 * {@code WornItemsMutationEpochPatch}.
 */
public class WornItemsInvEpochBumpAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.This Object self) {
        StormInventoryWeight.bumpWornItems(self);
    }
}
