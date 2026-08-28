package io.pzstorm.storm.entity;

/**
 * Implemented onto {@code zombie.characters.IsoGameCharacter} by {@code
 * IsoGameCharacterInvWeightMemoPatch} (the redefinition adds a {@code stormInvWeight} field plus
 * this accessor pair), so each character carries a one-slot memo for {@code getInventoryWeight()}.
 *
 * <p>Layout: a single volatile long packing {@code (epoch << 32) | floatToRawIntBits(weight)}. The
 * high half is compared against {@link io.pzstorm.storm.inventory.StormInventoryWeight#epoch}; on
 * match the low half is the weight. A volatile long read/write can never tear, so a racy
 * cross-thread reader sees either a fully consistent memo or a stale epoch (a miss) &mdash; never a
 * mixed value.
 */
public interface StormInvWeightHolder {

    long getStormInvWeight();

    void setStormInvWeight(long packed);
}
