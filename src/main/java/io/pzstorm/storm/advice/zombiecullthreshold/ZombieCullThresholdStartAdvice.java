package io.pzstorm.storm.advice.zombiecullthreshold;

import io.pzstorm.storm.patch.performance.StormZombieCullConfig;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoWorld;

/**
 * Overrides the result of {@code ZombieCountOptimiser.startCount()} when a Storm-controlled
 * threshold is set. Vanilla caps the threshold at 500 via the {@code ZombiesCountBeforeDelete}
 * sandbox option; this advice bypasses that cap by recomputing {@code zombieCountForDelete} against
 * {@link StormZombieCullConfig#getThreshold()}.
 *
 * <p>When the Storm threshold is {@code <= 0}, the value vanilla wrote is kept untouched and the
 * sandbox-option-driven culling runs as before.
 */
public class ZombieCullThresholdStartAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.FieldValue(value = "zombieCountForDelete", readOnly = false) int count) {
        int threshold = StormZombieCullConfig.getThreshold();
        if (threshold > 0) {
            count = Math.max(0, IsoWorld.instance.getCell().getZombieList().size() - threshold);
        }
    }
}
