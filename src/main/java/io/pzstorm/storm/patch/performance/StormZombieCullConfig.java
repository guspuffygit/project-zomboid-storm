package io.pzstorm.storm.patch.performance;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime toggle for {@link ZombieCullDisablePatch}.
 *
 * <p>When {@code disabled} is {@code true}, {@code ZombieCountOptimiser.incrementZombie} is
 * short-circuited — nothing is ever queued for culling and the {@code zombies-culled} stat stops
 * incrementing. When {@code false}, vanilla culling runs untouched.
 *
 * <p>The transformer is always registered on the server JVM, so toggling this flag takes effect on
 * the very next call to {@code incrementZombie} (volatile read in the advice). Initialised from
 * {@code -Dstorm.disableZombieCull}; adjustable at runtime via the Storm HTTP endpoint.
 */
public final class StormZombieCullConfig {

    private static final AtomicBoolean DISABLED =
            new AtomicBoolean(Boolean.getBoolean("storm.disableZombieCull"));

    private StormZombieCullConfig() {}

    public static boolean isDisabled() {
        return DISABLED.get();
    }

    /** Returns the value actually applied (same as {@code disabled}). */
    public static boolean setDisabled(boolean disabled) {
        DISABLED.set(disabled);
        return disabled;
    }
}
