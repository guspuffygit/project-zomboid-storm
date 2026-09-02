package io.pzstorm.storm.entity;

/**
 * Implemented onto {@code zombie.iso.sprite.IsoSprite} by {@code IsoSpriteFloorFlagsPatch}: a slot
 * for {@code StormFloorFlags}' cached natural/sand/dirt classification of the sprite's name (opaque
 * {@code Object} so the interface loads without Storm's helper).
 */
public interface StormSpriteFloorFlagsHolder {

    Object getStormFloorFlags();

    void setStormFloorFlags(Object entry);
}
