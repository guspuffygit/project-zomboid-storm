package io.pzstorm.storm.advice.animationplayerrecorderisactive;

import java.util.Set;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code AnimationPlayerRecorder.isAnimationRecorderActive(IsoMovingObject)}.
 *
 * <p>Vanilla enters a synchronized block on {@code animationRecorderTypeMask} to do a {@code
 * Set.contains(objectType)} lookup, then falls through to {@code IsoPlayer.anyPlayer(...)}, which
 * walks every player slot evaluating {@code shouldPlayerActivateRecordingOnObject} — a predicate
 * whose first real check is {@code animationRecorderMinRangeOfPlayer > 0}, so the whole walk is
 * wasted whenever the recorder is off. It is reached from {@code
 * IsoMovingObject.updateAnimationRecorder()} for every moving object every tick and from {@code
 * BaseVehicle.update}; the recorder is off in normal gameplay on both client and server. Measured
 * at 0.83% of the ATF server main thread with 145 connections (scan #8, 2026-09-02).
 *
 * <p>Fast path: if {@code animationRecorderActiveAll == false}, the type mask is empty, and {@code
 * animationRecorderMinRangeOfPlayer <= 0}, the method definitively returns false, so the body is
 * skipped and the default {@code false} is returned. Unsynchronized reads of these fields are safe
 * because vanilla's {@code isAnimationRecorderActiveAll()} accessor is itself unsynchronized (same
 * field), and {@code Set.isEmpty()} on a {@link java.util.HashSet} is a plain {@code size == 0}
 * read — a race with a concurrent {@code add} at worst falls through to vanilla one frame later,
 * which reacquires the lock and gets the up-to-date view.
 *
 * <p>The fields are read through {@link Advice.FieldValue}: the advice is inlined into {@code
 * AnimationPlayerRecorder} itself, so its private statics are directly accessible and no reflection
 * runs on the hot path.
 */
public class AnimationPlayerRecorderIsActiveAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.FieldValue("animationRecorderActiveAll") boolean activeAll,
            @Advice.FieldValue("animationRecorderMinRangeOfPlayer") float minRangeOfPlayer,
            @Advice.FieldValue("animationRecorderTypeMask") Set<?> typeMask) {
        return !activeAll && minRangeOfPlayer <= 0.0F && typeMask.isEmpty();
    }
}
