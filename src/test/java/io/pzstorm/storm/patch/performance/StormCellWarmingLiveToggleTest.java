package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

/**
 * Live toggling of cell warming through the {@code Storm.KeepCellsWarm} / {@code
 * Storm.MaxWarmCells} setters, and the postupdate-ownership / eviction-cap decisions that make a
 * live disable drain the warm set instead of handing detached cells to the vanilla loop.
 */
class StormCellWarmingLiveToggleTest implements UnitTest {

    @Test
    void warmerOwnsPostUpdateWhileEnabledOrDraining() {
        assertTrue(StormCellWarmer.ownsPostUpdate(true, 0));
        assertTrue(StormCellWarmer.ownsPostUpdate(true, 17));
        // Disabled with cells still warm: keep the body until the drain finishes.
        assertTrue(StormCellWarmer.ownsPostUpdate(false, 1));
        // Disabled and drained: vanilla / unload budget owns postupdate again.
        assertFalse(StormCellWarmer.ownsPostUpdate(false, 0));
    }

    @Test
    void drainingEvictsEverythingRegardlessOfConfiguredCap() {
        assertEquals(0, StormCellWarmer.effectiveEvictionCap(false, 128));
        assertEquals(0, StormCellWarmer.effectiveEvictionCap(false, 0));
    }

    @Test
    void enabledCapIsConfiguredValueOrNoPassWhenUnbounded() {
        assertEquals(128, StormCellWarmer.effectiveEvictionCap(true, 128));
        assertEquals(1, StormCellWarmer.effectiveEvictionCap(true, 1));
        assertEquals(-1, StormCellWarmer.effectiveEvictionCap(true, 0));
        assertEquals(-1, StormCellWarmer.effectiveEvictionCap(true, -3));
    }

    @Test
    void setEnabledRoundTrips() {
        boolean before = StormCellWarmingConfig.isEnabled();
        try {
            assertTrue(StormCellWarmingConfig.setEnabled(true));
            assertTrue(StormCellWarmingConfig.isEnabled());
            assertTrue(StormCellWarmer.isActive());
            assertFalse(StormCellWarmingConfig.setEnabled(false));
            assertFalse(StormCellWarmingConfig.isEnabled());
            // Nothing warm in a unit test, so a disable is immediately inactive.
            assertFalse(StormCellWarmer.isActive());
        } finally {
            StormCellWarmingConfig.setEnabled(before);
        }
    }

    @Test
    void setMaxWarmCellsClampsAndReturnsApplied() {
        int before = StormCellWarmingConfig.maxWarmCells();
        try {
            assertEquals(64, StormCellWarmingConfig.setMaxWarmCells(64));
            assertEquals(64, StormCellWarmingConfig.maxWarmCells());
            assertEquals(0, StormCellWarmingConfig.setMaxWarmCells(-5));
            assertEquals(
                    StormCellWarmingConfig.MAX_MAX_WARM_CELLS,
                    StormCellWarmingConfig.setMaxWarmCells(Integer.MAX_VALUE));
        } finally {
            StormCellWarmingConfig.setMaxWarmCells(before);
        }
    }

    @Test
    void defaultsMatchVanillaOff() {
        assertFalse(StormCellWarmingConfig.DEFAULT_ENABLED);
        assertEquals(128, StormCellWarmingConfig.DEFAULT_MAX_WARM_CELLS);
    }
}
