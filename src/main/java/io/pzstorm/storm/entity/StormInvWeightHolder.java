package io.pzstorm.storm.entity;

/**
 * Implemented onto {@code zombie.characters.IsoGameCharacter} by {@code
 * IsoGameCharacterInvWeightMemoPatch} (the redefinition adds the {@code stormInvWeight} and {@code
 * stormInvEpoch} fields plus these accessor pairs), so each character carries a one-slot memo for
 * {@code getInventoryWeight()} together with its own validity epoch.
 *
 * <p>Layout: {@code stormInvWeight} is a single volatile long packing {@code ((epoch + 1) << 32) |
 * floatToRawIntBits(weight)}; {@code stormInvEpoch} is a volatile int advanced by every mutation
 * that can change this character's weigh result (see {@link
 * io.pzstorm.storm.inventory.StormInventoryWeight}). The memo is valid iff its high half equals
 * {@code stormInvEpoch + 1} &mdash; the {@code + 1} keeps the zero-initialised pair from reading as
 * a hit with weight {@code 0.0}. A volatile long read/write can never tear, so a racy cross-thread
 * reader sees either a fully consistent memo or a stale epoch (a miss) &mdash; never a mixed value.
 */
public interface StormInvWeightHolder {

    long getStormInvWeight();

    void setStormInvWeight(long packed);

    int getStormInvEpoch();

    void setStormInvEpoch(int epoch);
}
