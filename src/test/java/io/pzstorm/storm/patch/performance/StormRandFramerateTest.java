package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.patch.networking.ServerLockFpsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StormRandFramerateTest {

    @AfterEach
    void resetLockFps() {
        ServerLockFpsConfig.setLockFps(ServerLockFpsConfig.DEFAULT_LOCK_FPS);
    }

    @Test
    void bitIdenticalToVanillaAtDefaultTenTps() {
        ServerLockFpsConfig.setLockFps(10);
        for (int chance : new int[] {0, 1, 2, 3, 7, 10, 30, 100, 1000, 123456, Integer.MAX_VALUE}) {
            // Vanilla server branch: (int)(chance * 0.33333334F).
            Assertions.assertEquals(
                    (int) (chance * 0.33333334F),
                    StormRandFramerate.adjustForServerFramerate(chance),
                    "chance=" + chance);
        }
    }

    @Test
    void scalesWithLockFps() {
        ServerLockFpsConfig.setLockFps(30);
        Assertions.assertEquals(300, StormRandFramerate.adjustForServerFramerate(300));
        ServerLockFpsConfig.setLockFps(60);
        Assertions.assertEquals(600, StormRandFramerate.adjustForServerFramerate(300));
        ServerLockFpsConfig.setLockFps(20);
        Assertions.assertEquals(200, StormRandFramerate.adjustForServerFramerate(300));
    }
}
