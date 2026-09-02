package io.pzstorm.storm.advice.ecsmemo;

import io.pzstorm.storm.entity.StormEcsMemoHolder;
import net.bytebuddy.asm.Advice;

/**
 * Advice for the {@code ECSEntity.setECSComponent(ECSComponent)} default method — the only path
 * that puts into an entity's component map. Drops the whole per-character lookup memo on exit so a
 * memoized negative result ({@link StormEcsMemoHolder#ABSENT}) can never outlive the registration
 * that would invalidate it. Positive entries re-validate by ownership and would survive on their
 * own; dropping them too just costs one re-probe per key, on a path that runs in constructors and
 * {@code IsoPlayer.setNpc} only.
 */
public class EcsSetComponentMemoClearAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.This Object self) {
        if (self instanceof StormEcsMemoHolder) {
            ((StormEcsMemoHolder) self).setStormEcsMemo(null);
        }
    }
}
