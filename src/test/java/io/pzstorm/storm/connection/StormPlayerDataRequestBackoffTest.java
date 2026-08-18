package io.pzstorm.storm.connection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StormPlayerDataRequestBackoffTest implements UnitTest {

    private static final long DEFAULT_COOLDOWN_NANOS = 5000L * 1_000_000L;

    @BeforeEach
    void resetGate() {
        StormPlayerDataRequestBackoff.reset();
        StormPlayerDataRequestBackoff.cooldownNanos = DEFAULT_COOLDOWN_NANOS;
    }

    @AfterEach
    void restoreGate() {
        StormPlayerDataRequestBackoff.reset();
        StormPlayerDataRequestBackoff.cooldownNanos = DEFAULT_COOLDOWN_NANOS;
    }

    @Test
    void allowsFirstRequestAndSuppressesRepeatWithinCooldown() {
        Object[] values = {(short) 42};
        assertFalse(StormPlayerDataRequestBackoff.shouldSuppress(values));
        assertTrue(StormPlayerDataRequestBackoff.shouldSuppress(values));
        assertTrue(StormPlayerDataRequestBackoff.shouldSuppress(values));
    }

    @Test
    void tracksIdsIndependently() {
        assertFalse(StormPlayerDataRequestBackoff.shouldSuppress(new Object[] {(short) 1}));
        assertFalse(StormPlayerDataRequestBackoff.shouldSuppress(new Object[] {(short) 2}));
        assertTrue(StormPlayerDataRequestBackoff.shouldSuppress(new Object[] {(short) 1}));
        assertTrue(StormPlayerDataRequestBackoff.shouldSuppress(new Object[] {(short) 2}));
    }

    @Test
    void allowsAgainAfterCooldownExpires() throws InterruptedException {
        StormPlayerDataRequestBackoff.cooldownNanos = 1_000_000L;
        Object[] values = {(short) 7};
        assertFalse(StormPlayerDataRequestBackoff.shouldSuppress(values));
        Thread.sleep(5L);
        assertFalse(StormPlayerDataRequestBackoff.shouldSuppress(values));
    }

    @Test
    void zeroOrNegativeCooldownDisablesSuppression() {
        StormPlayerDataRequestBackoff.cooldownNanos = 0L;
        Object[] values = {(short) 9};
        assertFalse(StormPlayerDataRequestBackoff.shouldSuppress(values));
        assertFalse(StormPlayerDataRequestBackoff.shouldSuppress(values));
    }

    @Test
    void failsOpenOnUnexpectedArgumentShapes() {
        assertFalse(StormPlayerDataRequestBackoff.shouldSuppress(null));
        assertFalse(StormPlayerDataRequestBackoff.shouldSuppress(new Object[0]));
        assertFalse(StormPlayerDataRequestBackoff.shouldSuppress(new Object[] {"not-a-short"}));
        assertFalse(StormPlayerDataRequestBackoff.shouldSuppress(new Object[] {null}));
        assertFalse(
                StormPlayerDataRequestBackoff.shouldSuppress(new Object[] {(short) 1, (short) 2}));
    }

    @Test
    void countsAllowedAndSuppressed() {
        Object[] values = {(short) 3};
        StormPlayerDataRequestBackoff.shouldSuppress(values);
        StormPlayerDataRequestBackoff.shouldSuppress(values);
        StormPlayerDataRequestBackoff.shouldSuppress(values);
        assertTrue(StormPlayerDataRequestBackoff.allowedCount == 1);
        assertTrue(StormPlayerDataRequestBackoff.suppressedCount == 2);
    }
}
