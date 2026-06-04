package io.pzstorm.storm.advice.isoobjectidalloc;

import io.pzstorm.storm.patch.fixes.IsoObjectIDProbe;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/**
 * Replaces {@code zombie.network.IsoObjectID.allocateID()} with a probe-for-free implementation.
 * See {@link io.pzstorm.storm.patch.fixes.IsoObjectIDAllocateFixPatch} for the rationale and the
 * full mitosis-causality writeup.
 *
 * <p>Pattern: enter advice always returns {@code true} to skip the original body; exit advice
 * computes the new ID via {@link IsoObjectIDProbe#nextFreeId(short, Map)} and writes both the
 * cursor field and the method return value via writable {@code @Advice.FieldValue} /
 * {@code @Advice.Return}. Same shape as {@code StatsGetAdvice}.
 *
 * <p>{@code nextId} is only advanced when a free slot is found. On an exhausted pool ({@link
 * IsoObjectIDProbe#ID_INVALID}) the cursor stays put so the next call probes from the same position
 * — useful when a single slot has just been freed.
 */
public class IsoObjectIDAllocateAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return true;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.FieldValue(value = "nextId", readOnly = false) short nextId,
            @Advice.FieldValue("idToObjectMap") Map<Short, ?> idToObjectMap,
            @Advice.Return(readOnly = false) short ret) {
        short newId = IsoObjectIDProbe.nextFreeId(nextId, idToObjectMap);
        if (newId != IsoObjectIDProbe.ID_INVALID) {
            nextId = newId;
        }
        ret = newId;
    }
}
