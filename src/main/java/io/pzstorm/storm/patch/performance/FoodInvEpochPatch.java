package io.pzstorm.storm.patch.performance;

/**
 * Inventory-weight epoch sources on {@code zombie.inventory.types.Food}, whose {@code
 * getActualWeight()} override scales by remaining hunger / thirst: {@code setHungChange}, {@code
 * setThirstChange}, {@code setBaseHunger}. Server-only by registration gate.
 */
public class FoodInvEpochPatch extends NamedMethodsAdvicePatch {

    public FoodInvEpochPatch() {
        super(
                "zombie.inventory.types.Food",
                "io.pzstorm.storm.advice.inventoryweight.InventoryItemInvEpochBumpAdvice",
                "setHungChange",
                "setThirstChange",
                "setBaseHunger");
    }
}
