package io.pzstorm.storm.patch.performance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VirtualAnimalTickIntervalTest {

    @AfterEach
    void resetInterval() {
        VirtualAnimalTickInterval.setCurrentTickIntervalForTest(
                VirtualAnimalTickInterval.DEFAULT_TICK_INTERVAL);
    }

    @Test
    void strideZeroNeverRuns() {
        for (long frame = 0; frame < 20; frame++) {
            Assertions.assertFalse(VirtualAnimalTickInterval.shouldRunOnFrame(0, frame));
        }
    }

    @Test
    void strideOneAlwaysRuns() {
        for (long frame = 0; frame < 20; frame++) {
            Assertions.assertTrue(VirtualAnimalTickInterval.shouldRunOnFrame(1, frame));
        }
    }

    @Test
    void strideFourRunsExactlyOncePerWindow() {
        for (long windowStart = 0; windowStart < 40; windowStart += 4) {
            int runs = 0;
            for (long frame = windowStart; frame < windowStart + 4; frame++) {
                if (VirtualAnimalTickInterval.shouldRunOnFrame(4, frame)) {
                    runs++;
                }
            }
            Assertions.assertEquals(1, runs, "window at " + windowStart);
        }
        Assertions.assertTrue(VirtualAnimalTickInterval.shouldRunOnFrame(4, 0));
        Assertions.assertTrue(VirtualAnimalTickInterval.shouldRunOnFrame(4, 8));
        Assertions.assertFalse(VirtualAnimalTickInterval.shouldRunOnFrame(4, 9));
    }

    @Test
    void setterClampsToBounds() {
        Assertions.assertEquals(
                VirtualAnimalTickInterval.MIN_TICK_INTERVAL,
                VirtualAnimalTickInterval.setTickInterval(-5));
        Assertions.assertEquals(
                VirtualAnimalTickInterval.MAX_TICK_INTERVAL,
                VirtualAnimalTickInterval.setTickInterval(999));
        Assertions.assertEquals(4, VirtualAnimalTickInterval.setTickInterval(4));
        Assertions.assertEquals(4, VirtualAnimalTickInterval.getCurrentTickInterval());
    }
}
