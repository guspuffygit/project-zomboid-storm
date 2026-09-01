package io.pzstorm.storm.advice.client.ballisticsnullguard;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bookkeeping for the frames {@link CombatManagerBallisticsNullGuardAdvice} rescued. Counts every
 * skipped call and logs the first one, so a report that hits the guard still records the state that
 * would have frozen a vanilla client without turning into its own per-frame wall.
 */
public class BallisticsNullGuard {

    /** Calls that reached the reticle test with a null controller and were skipped. */
    public static final AtomicLong SKIPPED = new AtomicLong();

    public static void onNullController() {
        if (SKIPPED.incrementAndGet() == 1) {
            LOGGER.warn(
                    "CombatManagerBallisticsNullGuardPatch: local player's BallisticsController"
                            + " was null during the reticle target test; fell back to the aim-cone"
                            + " check for this frame");
        }
    }
}
