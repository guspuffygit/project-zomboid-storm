package io.pzstorm.storm.animation;

import java.util.HashMap;

/** One live probe for an existing bone index; null asks the caller to keep the native path. */
public final class StormBoneIndexLookup {
    private StormBoneIndexLookup() {}

    public static Integer existingIndex(HashMap<String, Integer> indices, String boneName) {
        // A mod's HashMap subclass may override containsKey/get with different behavior.
        if (indices == null || indices.getClass() != HashMap.class) return null;
        return indices.get(boneName);
    }
}
