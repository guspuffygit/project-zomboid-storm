package io.pzstorm.storm.patch.performance;

/**
 * Inventory-weight epoch sources on {@code zombie.inventory.InventoryItem}: {@code setActualWeight}
 * (drainable deltas route through it), {@code setCurrentAmmoCount}, {@code setAttachedSlot} (hotbar
 * weight rule), {@code setName} (the {@code displayName == fullType} zero-weight rule), {@code
 * setCustomWeight}, {@code addExtraItem} and {@code load} (resets ammo / slot). See {@link
 * io.pzstorm.storm.inventory.StormInventoryWeight}. Server-only by registration gate.
 */
public class InventoryItemInvEpochPatch extends NamedMethodsAdvicePatch {

    public InventoryItemInvEpochPatch() {
        super(
                "zombie.inventory.InventoryItem",
                "io.pzstorm.storm.advice.inventoryweight.InventoryItemInvEpochBumpAdvice",
                "setActualWeight",
                "setCurrentAmmoCount",
                "setAttachedSlot",
                "setName",
                "setCustomWeight",
                "addExtraItem",
                "load");
    }
}
