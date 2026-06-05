package io.pzstorm.storm.patch.performance;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime toggles for {@link ZombieCullDisablePatch} and {@link ZombieCullThresholdPatch}.
 *
 * <p>When {@code disabled} is {@code true}, {@code ZombieCountOptimiser.incrementZombie} is
 * short-circuited — nothing is ever queued for culling and the {@code zombies-culled} stat stops
 * incrementing. When {@code false}, vanilla culling runs untouched.
 *
 * <p>When {@code threshold > 0}, {@code startCount}'s result is overwritten so the cull target
 * becomes {@code max(0, liveZombies - threshold)} instead of being capped by the sandbox option's
 * 500 max, AND {@code incrementZombie}'s missing decrement is patched in — so per frame we queue at
 * most {@code excess} zombies rather than ~10% of the whole population. When {@code threshold <=
 * 0}, both behaviours fall through to vanilla.
 *
 * <p>Both transformers are always registered on the server JVM, so toggling either flag takes
 * effect on the very next call (volatile read in the advice). Initialised from {@code
 * -Dstorm.disableZombieCull} and {@code -Dstorm.zombieCullThreshold}; adjustable at runtime via the
 * Storm HTTP endpoints.
 */
public final class StormZombieCullConfig {

    private static final AtomicBoolean DISABLED =
            new AtomicBoolean(Boolean.getBoolean("storm.disableZombieCull"));

    private static final AtomicInteger THRESHOLD =
            new AtomicInteger(Integer.getInteger("storm.zombieCullThreshold", -1));

    private StormZombieCullConfig() {}

    public static boolean isDisabled() {
        return DISABLED.get();
    }

    /** Returns the value actually applied (same as {@code disabled}). */
    public static boolean setDisabled(boolean disabled) {
        DISABLED.set(disabled);
        return disabled;
    }

    /** Returns the current Storm-controlled cull threshold; {@code <= 0} means "no override". */
    public static int getThreshold() {
        return THRESHOLD.get();
    }

    /**
     * Sets the Storm-controlled cull threshold. Any positive value engages the patch; {@code <= 0}
     * disables the override and lets vanilla culling run. Returns the value actually applied.
     */
    public static int setThreshold(int threshold) {
        THRESHOLD.set(threshold);
        return threshold;
    }
}
