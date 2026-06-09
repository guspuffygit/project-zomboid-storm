package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime knob for {@link ZombieCullDisablePatch} and {@link ZombieCullThresholdPatch}.
 *
 * <p>A single {@code threshold} value drives both patches:
 *
 * <ul>
 *   <li>{@code threshold == 0} — {@code ZombieCountOptimiser.incrementZombie} is short-circuited
 *       and nothing is ever queued for culling. The {@code zombies-culled} stat stops incrementing.
 *   <li>{@code threshold > 0} — {@code startCount}'s result is overwritten so the cull target
 *       becomes {@code max(0, liveZombies - threshold)} instead of being capped by the vanilla
 *       {@code ZombiesCountBeforeDelete} sandbox option's 500 max, AND {@code incrementZombie}'s
 *       missing decrement is patched in so per frame we queue at most {@code excess} zombies rather
 *       than ~10% of the whole population.
 * </ul>
 *
 * <p>Both transformers are always registered on the server JVM; the threshold is read on every
 * advice entry (volatile), so live updates take effect on the next call. Sourced from the {@code
 * Storm.ZombieCullThreshold} sandbox option; adjustable at runtime via {@link #setThreshold(int)}
 * (which the Storm HTTP endpoint forwards through).
 */
public final class StormZombieCullConfig {

    /** Vanilla cap on the {@code ZombiesCountBeforeDelete} sandbox option. */
    public static final int DEFAULT_THRESHOLD = 500;

    private static final AtomicInteger THRESHOLD = new AtomicInteger(DEFAULT_THRESHOLD);

    private StormZombieCullConfig() {}

    /** Current Storm-controlled cull threshold. {@code 0} = culling disabled. */
    public static int getThreshold() {
        return THRESHOLD.get();
    }

    /**
     * Sets the Storm-controlled cull threshold. {@code 0} disables culling entirely; any positive
     * value engages the override. Returns the value actually applied.
     */
    public static int setThreshold(int threshold) {
        int applied = Math.max(0, threshold);
        THRESHOLD.set(applied);
        StormPerformanceSandboxMetrics.setZombieCullThreshold(applied);
        return applied;
    }
}
