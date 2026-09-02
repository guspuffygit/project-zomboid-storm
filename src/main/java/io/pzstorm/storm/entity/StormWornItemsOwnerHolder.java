package io.pzstorm.storm.entity;

/**
 * Implemented onto {@code zombie.characters.WornItems.WornItems} by {@code
 * WornItemsMutationEpochPatch} (adds a {@code stormOwner} field plus this accessor pair). Vanilla
 * {@code WornItems} has no back-reference to its character, but a worn-item change must invalidate
 * that character's inventory-weight memo (worn clothing gets the equipped weight multiplier), so
 * the character stamps itself here from {@code onWornItemsChanged} / {@code setInventory} / the
 * hand setters and on every memo miss. Every {@code WornItems} instance is owned by exactly one
 * character ({@code setWornItems} copies; it never shares).
 */
public interface StormWornItemsOwnerHolder {

    Object getStormOwner();

    void setStormOwner(Object owner);
}
