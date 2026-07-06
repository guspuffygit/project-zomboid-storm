package io.pzstorm.storm.patch.performance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InventoryItemSweepTickIntervalTest {

    @AfterEach
    void resetInterval() {
        InventoryItemSweepTickInterval.setCurrentTickIntervalForTest(
                InventoryItemSweepTickInterval.DEFAULT_TICK_INTERVAL);
    }

    @Test
    void strideOneAlwaysRuns() {
        for (long frame = 0; frame < 20; frame++) {
            Assertions.assertTrue(InventoryItemSweepTickInterval.shouldRunOnFrame(1, frame));
        }
    }

    @Test
    void strideFourRunsExactlyOncePerWindow() {
        for (long windowStart = 0; windowStart < 40; windowStart += 4) {
            int runs = 0;
            for (long frame = windowStart; frame < windowStart + 4; frame++) {
                if (InventoryItemSweepTickInterval.shouldRunOnFrame(4, frame)) {
                    runs++;
                }
            }
            Assertions.assertEquals(1, runs, "window at " + windowStart);
        }
    }

    @Test
    void phaseOffsetStaggersFromVirtualAnimalStride() {
        // Both strides at the same N must never execute on the same frame.
        for (long frame = 0; frame < 40; frame++) {
            boolean sweep = InventoryItemSweepTickInterval.shouldRunOnFrame(4, frame);
            boolean animals = VirtualAnimalTickInterval.shouldRunOnFrame(4, frame);
            Assertions.assertFalse(sweep && animals, "both ran at frame " + frame);
        }
    }

    @Test
    void setterClampsToBounds() {
        Assertions.assertEquals(
                InventoryItemSweepTickInterval.MIN_TICK_INTERVAL,
                InventoryItemSweepTickInterval.setTickInterval(0));
        Assertions.assertEquals(
                InventoryItemSweepTickInterval.MAX_TICK_INTERVAL,
                InventoryItemSweepTickInterval.setTickInterval(999));
        Assertions.assertEquals(10, InventoryItemSweepTickInterval.setTickInterval(10));
        Assertions.assertEquals(10, InventoryItemSweepTickInterval.getCurrentTickInterval());
    }
}
