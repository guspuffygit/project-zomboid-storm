package io.pzstorm.storm.entity;

/**
 * Implemented onto {@code zombie.inventory.InventoryItem} by {@code InventoryItemVisualFieldPatch},
 * exposing the protected {@code visual} field ({@code ItemVisual}, typed {@code Object} here so the
 * interface loads without game classes). Lets {@code StormClothingVisuals} tell whether {@code
 * getVisual()} would return an already-built visual without paying for the {@code
 * getClothingItem()} resolution it performs every call.
 */
public interface StormVisualFieldHolder {

    Object getStormVisualField();
}
