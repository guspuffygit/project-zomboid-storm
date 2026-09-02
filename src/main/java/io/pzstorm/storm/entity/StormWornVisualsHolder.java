package io.pzstorm.storm.entity;

/**
 * Implemented onto {@code zombie.characters.IsoGameCharacter} by {@code
 * IsoGameCharacterWornVisualsMemoPatch}: a per-character slot for {@code StormClothingVisuals}'
 * worn-item → visual memo (an opaque {@code Object} so the interface loads without Storm's helper).
 * Main-thread only, like every other per-character memo.
 */
public interface StormWornVisualsHolder {

    Object getStormWornVisuals();

    void setStormWornVisuals(Object memo);
}
