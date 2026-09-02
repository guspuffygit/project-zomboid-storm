package io.pzstorm.storm.patch.performance;

/**
 * Inventory-weight epoch sources on {@code zombie.inventory.types.HandWeapon}, whose {@code
 * getActualWeight()} override sums attached weapon-part modifiers: {@code setWeaponPart}, {@code
 * clearWeaponPart}, {@code clearAllWeaponParts} (the only mutators of the attachments map).
 * Server-only by registration gate.
 */
public class HandWeaponInvEpochPatch extends NamedMethodsAdvicePatch {

    public HandWeaponInvEpochPatch() {
        super(
                "zombie.inventory.types.HandWeapon",
                "io.pzstorm.storm.advice.inventoryweight.InventoryItemInvEpochBumpAdvice",
                "setWeaponPart",
                "clearWeaponPart",
                "clearAllWeaponParts");
    }
}
