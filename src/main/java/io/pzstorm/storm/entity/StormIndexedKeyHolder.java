package io.pzstorm.storm.entity;

/**
 * Implemented onto {@code CharacterStat}, {@code MoodleType} and {@code CharacterTrait} by the
 * {@code *IndexPatch} transformers: each key gets a dense, per-class index at construction, which
 * {@link io.pzstorm.storm.characters.StormIndexedMap} uses as an array slot in place of a hash
 * probe. All three key classes are identity-compared in vanilla (no {@code equals}/{@code
 * hashCode}), so the index is exactly as discriminating as the {@code HashMap} it replaces.
 */
public interface StormIndexedKeyHolder {

    int getStormIndex();
}
