package io.pzstorm.storm.advice.animationplayerrecorderisactive;

import java.lang.reflect.Field;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoMovingObject;

/**
 * Advice for {@code AnimationPlayerRecorder.isAnimationRecorderActive(IsoMovingObject)}.
 *
 * <p>Vanilla body enters a synchronized block on {@code animationRecorderTypeMask} on every call to
 * do a {@code Set.contains(objectType)} lookup, then falls through to an {@code
 * IsoPlayer.anyPlayer} scan when the min-range trigger is set. This is called from every character
 * update / render path, and the recorder is off in normal gameplay — but the sync-entry cost is
 * still paid on every call.
 *
 * <p>Fast path: if {@code animationRecorderActiveAll == false}, the type mask is empty, and {@code
 * animationRecorderMinRangeOfPlayer <= 0}, the method definitively returns false. Unsynchronized
 * reads of these fields are safe because vanilla's {@code isAnimationRecorderActiveAll()} accessor
 * is itself unsynchronized (same field), and {@code Set.isEmpty()} on a {@link java.util.HashSet}
 * is a simple {@code size == 0} read — a race with a concurrent {@code add} at worst causes us to
 * fall through to vanilla one frame later, which reacquires sync and gets the up-to-date view.
 *
 * <p>Encoding: enter returns {@code 1} to short-circuit vanilla with a known-false answer, {@code
 * 0} to fall through. Exit maps {@code 1} → false.
 */
public class AnimationPlayerRecorderIsActiveAdvice {

    public static final Field ACTIVE_ALL_FIELD;
    public static final Field TYPE_MASK_FIELD;
    public static final Field MIN_RANGE_FIELD;

    static {
        try {
            Class<?> apr =
                    Class.forName(
                            "zombie.core.skinnedmodel.animation.debug.AnimationPlayerRecorder");
            ACTIVE_ALL_FIELD = apr.getDeclaredField("animationRecorderActiveAll");
            ACTIVE_ALL_FIELD.setAccessible(true);
            TYPE_MASK_FIELD = apr.getDeclaredField("animationRecorderTypeMask");
            TYPE_MASK_FIELD.setAccessible(true);
            MIN_RANGE_FIELD = apr.getDeclaredField("animationRecorderMinRangeOfPlayer");
            MIN_RANGE_FIELD.setAccessible(true);
        } catch (Throwable t) {
            throw new RuntimeException("failed to bind AnimationPlayerRecorder internals", t);
        }
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(@Advice.Argument(0) IsoMovingObject obj) {
        try {
            if (!ACTIVE_ALL_FIELD.getBoolean(null) && MIN_RANGE_FIELD.getFloat(null) <= 0.0F) {
                Object mask = TYPE_MASK_FIELD.get(null);
                if (mask instanceof Set && ((Set<?>) mask).isEmpty()) {
                    return 1;
                }
            }
        } catch (Throwable t) {
            return 0;
        }
        return 0;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int code, @Advice.Return(readOnly = false) boolean ret) {
        if (code == 1) {
            ret = false;
        }
    }
}
