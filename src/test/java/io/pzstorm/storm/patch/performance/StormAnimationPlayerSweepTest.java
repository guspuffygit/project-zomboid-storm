package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

/**
 * Covers the reclaim predicate and the absence clock that feeds it. The sweep's other half —
 * walking the pool's in-use set and calling {@code releaseAnimationPlayer()} — needs a live {@code
 * IsoCell} and is exercised against the running server instead.
 */
class StormAnimationPlayerSweepTest implements UnitTest {

    private static final long GRACE = 60_000L;

    private static final long NOW = 1_000_000L;

    @Test
    void inWorldCharactersAreNeverReclaimed() {
        assertFalse(
                StormAnimationPlayerSweep.isReclaimable(true, GRACE * 10, GRACE),
                "a character in the cell keeps its animation player no matter how long it has"
                        + " been absent before");
    }

    @Test
    void reclaimsOnlyAfterTheGracePeriod() {
        assertFalse(
                StormAnimationPlayerSweep.isReclaimable(false, GRACE - 1, GRACE),
                "one millisecond short of the grace period must not fire");
        assertTrue(
                StormAnimationPlayerSweep.isReclaimable(false, GRACE, GRACE),
                "exactly at the grace period fires");
        assertTrue(StormAnimationPlayerSweep.isReclaimable(false, GRACE * 10, GRACE));
    }

    @Test
    void vanillaRemovalStampIsPreferredOverTheObservedClock() {
        assertEquals(
                GRACE * 2,
                StormAnimationPlayerSweep.resolveAgeMs(NOW - GRACE * 2, NOW, NOW),
                "a stamped character ages from the stamp, not from the sweep that first saw it");
    }

    /**
     * Dead animals and zombies observed in production sit out of the world with {@code
     * removedFromWorldMs == 0} because no removal path ever ran. They must still age, from the
     * first sweep that saw them.
     */
    @Test
    void unstampedCharactersAgeFromTheFirstSweepThatSawThem() {
        assertEquals(
                0,
                StormAnimationPlayerSweep.resolveAgeMs(0L, NOW, NOW),
                "the sweep that first sees a character gives it no age at all");
        assertEquals(GRACE, StormAnimationPlayerSweep.resolveAgeMs(0L, NOW - GRACE, NOW));
        assertFalse(StormAnimationPlayerSweep.isReclaimable(false, 0L, GRACE));
        assertTrue(
                StormAnimationPlayerSweep.isReclaimable(
                        false,
                        StormAnimationPlayerSweep.resolveAgeMs(0L, NOW - GRACE, NOW),
                        GRACE));
    }

    /**
     * Vanilla's deferred-removal windows are 5s, so an animal virtualized and re-realized inside
     * one is back in the world long before the default grace period expires.
     */
    @Test
    void vanillaDeferredRemovalWindowIsWellInsideTheGracePeriod() {
        long vanillaWindowMs = 5_000L;
        assertFalse(
                StormAnimationPlayerSweep.isReclaimable(
                        false,
                        StormAnimationPlayerSweep.resolveAgeMs(NOW - vanillaWindowMs, NOW, NOW),
                        GRACE));
    }

    @Test
    void clockGoingBackwardsDoesNotReclaim() {
        assertEquals(
                0,
                StormAnimationPlayerSweep.resolveAgeMs(NOW + GRACE, NOW, NOW),
                "a removal stamped in the future must not be treated as long expired");
        assertEquals(0, StormAnimationPlayerSweep.resolveAgeMs(-1L, NOW + GRACE, NOW));
    }
}
