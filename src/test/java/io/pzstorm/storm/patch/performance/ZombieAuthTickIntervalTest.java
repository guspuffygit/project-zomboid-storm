package io.pzstorm.storm.patch.performance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ZombieAuthTickIntervalTest {

    @AfterEach
    void resetInterval() {
        ZombieAuthTickInterval.setCurrentTickIntervalForTest(
                ZombieAuthTickInterval.DEFAULT_TICK_INTERVAL);
    }

    @Test
    void strideOneAlwaysRuns() {
        for (long frame = 0; frame < 20; frame++) {
            Assertions.assertTrue(ZombieAuthTickInterval.shouldRunForZombie(1, frame, 7));
        }
    }

    @Test
    void strideFourRunsExactlyOncePerWindowPerZombie() {
        // Include a negative id: IsoZombie.getOnlineID() returns a short that can wrap negative.
        for (int id : new int[] {0, 1, 3, 12345, -7, Short.MIN_VALUE}) {
            for (long windowStart = 0; windowStart < 40; windowStart += 4) {
                int runs = 0;
                for (long frame = windowStart; frame < windowStart + 4; frame++) {
                    if (ZombieAuthTickInterval.shouldRunForZombie(4, frame, id)) {
                        runs++;
                    }
                }
                Assertions.assertEquals(1, runs, "id=" + id + " window at " + windowStart);
            }
        }
    }

    @Test
    void zombiesArePhaseSpreadByOnlineId() {
        // At any single frame, ids 0..3 with stride 4 must land on 4 distinct phases —
        // exactly one of them runs.
        long frame = 100;
        int runs = 0;
        for (int id = 0; id < 4; id++) {
            if (ZombieAuthTickInterval.shouldRunForZombie(4, frame, id)) {
                runs++;
            }
        }
        Assertions.assertEquals(1, runs);
    }

    @Test
    void setterClampsToBounds() {
        Assertions.assertEquals(
                ZombieAuthTickInterval.MIN_TICK_INTERVAL,
                ZombieAuthTickInterval.setTickInterval(0));
        Assertions.assertEquals(
                ZombieAuthTickInterval.MAX_TICK_INTERVAL,
                ZombieAuthTickInterval.setTickInterval(999));
        Assertions.assertEquals(4, ZombieAuthTickInterval.setTickInterval(4));
        Assertions.assertEquals(4, ZombieAuthTickInterval.getCurrentTickInterval());
    }
}
