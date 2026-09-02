package io.pzstorm.storm.entity;

import java.util.Map;

/**
 * Implemented onto {@code Stats}, {@code Moodles} and {@code CharacterTraits} by the {@code
 * *IndexedMapPatch} transformers: the slot that holds the {@link
 * io.pzstorm.storm.characters.StormIndexedMap} every read of the vanilla {@code private final Map}
 * field is redirected to.
 */
public interface StormIndexedMapHolder {

    Map<Object, Object> getStormMap();

    void setStormMap(Map<Object, Object> map);
}
