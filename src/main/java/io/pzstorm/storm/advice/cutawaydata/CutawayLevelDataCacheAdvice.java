package io.pzstorm.storm.advice.cutawaydata;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Advice for {@code zombie.iso.fboRenderChunk.FBORenderCutaways$ChunkLevelsData.getDataForLevel}.
 *
 * <p>The vanilla lookup is a {@code TIntObjectHashMap.get(level + 32)} and the method is hammered
 * from the FBO chunk render path ({@code IsoChunk.getCutawayDataForLevel} showed 0.6-0.9% of
 * MainThread as a hot leaf while driving). The key space is a fixed {@code [-32, 31]}, so a plain
 * 64-slot array indexed by {@code level + 32} replaces the hash for repeat lookups.
 *
 * <p>Cache safety: the backing {@code levelData} map is append-only — entries are created lazily in
 * {@code getDataForLevel} and never removed or replaced for the lifetime of the {@code
 * ChunkLevelsData} (which is a {@code final} per-{@code IsoChunk} field; {@code removeFromWorld}
 * and {@code invalidateAll} mutate the values, not the map). The array therefore mirrors map
 * membership exactly: a hit returns the same object vanilla would, a miss falls through to the
 * vanilla body and the created entry is captured on exit.
 *
 * <p>The advice deliberately uses {@code Object[]}/{@code Object} so it references no game types;
 * the write back into the typed return value uses dynamic typing (a {@code checkcast} on the cached
 * object, which is always a {@code ChunkLevelData} taken from this method's own return).
 */
public class CutawayLevelDataCacheAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object onEnter(
            @Advice.FieldValue(value = "storm$levelCache", readOnly = false) Object[] cache,
            @Advice.Argument(0) int level) {
        if (level < -32 || level > 31) {
            return null;
        }
        if (cache == null) {
            cache = new Object[64];
            return null;
        }
        return cache[level + 32];
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter Object cached,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object result,
            @Advice.FieldValue("storm$levelCache") Object[] cache,
            @Advice.Argument(0) int level) {
        if (cached != null) {
            result = cached;
        } else if (result != null && cache != null && level >= -32 && level <= 31) {
            cache[level + 32] = result;
        }
    }
}
