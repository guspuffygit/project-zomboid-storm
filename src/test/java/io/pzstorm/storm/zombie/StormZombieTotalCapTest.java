package io.pzstorm.storm.zombie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StormZombieTotalCapTest {

    @AfterEach
    void resetCap() {
        StormZombieTotalCap.setMaxTotal(StormZombieTotalCap.DEFAULT_MAX_TOTAL);
    }

    @Test
    void defaultsToDisabled() {
        Assertions.assertEquals(0, StormZombieTotalCap.DEFAULT_MAX_TOTAL);
        Assertions.assertFalse(StormZombieTotalCap.enabled());
    }

    @Test
    void setterClampsToBounds() {
        Assertions.assertEquals(
                StormZombieTotalCap.MIN, StormZombieTotalCap.setMaxTotal(Integer.MIN_VALUE));
        Assertions.assertEquals(
                StormZombieTotalCap.MAX, StormZombieTotalCap.setMaxTotal(Integer.MAX_VALUE));
        Assertions.assertEquals(7000, StormZombieTotalCap.setMaxTotal(7000));
        Assertions.assertEquals(7000, StormZombieTotalCap.maxTotal());
    }

    @Test
    void zeroDisablesAndAnyPositiveEnables() {
        StormZombieTotalCap.setMaxTotal(1);
        Assertions.assertTrue(StormZombieTotalCap.enabled());
        StormZombieTotalCap.setMaxTotal(0);
        Assertions.assertFalse(StormZombieTotalCap.enabled());
    }

    /** A cap the short online-ID pool could never reach would be silently unreachable. */
    @Test
    void ceilingStaysUnderTheOnlineIdAddressSpace() {
        Assertions.assertTrue(StormZombieTotalCap.MAX < Short.MAX_VALUE);
    }

    @Test
    void sweepIsInertWhileDisabled() {
        StormZombieTotalCap.setMaxTotal(0);
        Assertions.assertDoesNotThrow(StormZombieTotalCap::onServerTick);
    }
}
