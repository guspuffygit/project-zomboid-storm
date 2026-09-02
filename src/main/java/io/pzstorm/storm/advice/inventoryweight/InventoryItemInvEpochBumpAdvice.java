package io.pzstorm.storm.advice.inventoryweight;

import io.pzstorm.storm.inventory.StormInventoryWeight;
import net.bytebuddy.asm.Advice;

/**
 * Exit advice (including exceptional exit) for item-level setters whose value feeds {@code
 * getInventoryWeight()} &mdash; routes to the character holding the item, if any. Applied by {@code
 * InventoryItemInvEpochPatch}, {@code FoodInvEpochPatch} and {@code HandWeaponInvEpochPatch}.
 */
public class InventoryItemInvEpochBumpAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.This Object self) {
        StormInventoryWeight.bumpItem(self);
    }
}
