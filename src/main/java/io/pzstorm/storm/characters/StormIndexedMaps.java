package io.pzstorm.storm.characters;

import io.pzstorm.storm.entity.StormIndexedMapHolder;
import java.util.Map;

/**
 * Substitution target for every read of the vanilla {@code private final Map} field inside {@code
 * Stats} ({@code stats}), {@code Moodles} ({@code moodles}) and {@code CharacterTraits} ({@code
 * traits}) — the receiver arrives as the argument. Hands back the holder's {@link StormIndexedMap},
 * creating it on first use; the vanilla field is still initialised by the constructor but never
 * read again, so no write to a final field is needed. Unreachable unless the holder interface was
 * woven, because the substitution is part of the same transform.
 */
public final class StormIndexedMaps {

    private StormIndexedMaps() {}

    public static Map<Object, Object> mapOf(Object holder) {
        StormIndexedMapHolder h = (StormIndexedMapHolder) holder;
        Map<Object, Object> map = h.getStormMap();
        if (map == null) {
            map = new StormIndexedMap();
            h.setStormMap(map);
        }
        return map;
    }

    /**
     * {@code Stats.get(CharacterStat)} body: the stored {@code Float} unboxed, or {@code
     * defaultValue} when the stat was never set. Nothing is boxed on either path.
     */
    public static float getFloat(Object holder, Object key, float defaultValue) {
        Object v = ((StormIndexedMap) mapOf(holder)).getIndexed(key);
        return v != null ? ((Float) v).floatValue() : defaultValue;
    }
}
