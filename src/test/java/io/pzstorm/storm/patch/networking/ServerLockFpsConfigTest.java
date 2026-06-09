package io.pzstorm.storm.patch.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.core.PerformanceSettings;

/**
 * Verifies the server-side {@code lockFps} controller's clamping and live setter (which propagates
 * to {@link PerformanceSettings#setLockFPS(int)}).
 */
class ServerLockFpsConfigTest implements UnitTest {

    private int savedConfigValue;
    private int savedPerfSettings;

    @BeforeEach
    void captureState() {
        savedConfigValue = ServerLockFpsConfig.getCurrentLockFps();
        savedPerfSettings = PerformanceSettings.getLockFPS();
        ServerLockFpsConfig.setCurrentLockFpsForTest(ServerLockFpsConfig.DEFAULT_LOCK_FPS);
    }

    @AfterEach
    void restoreState() {
        ServerLockFpsConfig.setCurrentLockFpsForTest(savedConfigValue);
        PerformanceSettings.setLockFPS(savedPerfSettings);
    }

    // -------- setLockFps() --------

    @Test
    void setLockFpsAppliesAndPropagatesToPerformanceSettings() {
        int applied = ServerLockFpsConfig.setLockFps(30);
        assertEquals(30, applied);
        assertEquals(30, ServerLockFpsConfig.getCurrentLockFps());
        assertEquals(30, PerformanceSettings.getLockFPS());
    }

    @Test
    void setLockFpsClampsBelowMinimum() {
        int applied = ServerLockFpsConfig.setLockFps(-3);
        assertEquals(ServerLockFpsConfig.MIN_LOCK_FPS, applied);
        assertEquals(ServerLockFpsConfig.MIN_LOCK_FPS, ServerLockFpsConfig.getCurrentLockFps());
        assertEquals(ServerLockFpsConfig.MIN_LOCK_FPS, PerformanceSettings.getLockFPS());
    }

    @Test
    void setLockFpsClampsAboveMaximum() {
        int applied = ServerLockFpsConfig.setLockFps(99_999);
        assertEquals(ServerLockFpsConfig.MAX_LOCK_FPS, applied);
        assertEquals(ServerLockFpsConfig.MAX_LOCK_FPS, ServerLockFpsConfig.getCurrentLockFps());
        assertEquals(ServerLockFpsConfig.MAX_LOCK_FPS, PerformanceSettings.getLockFPS());
    }

    @Test
    void setLockFpsAcceptsBoundaryValues() {
        assertEquals(
                ServerLockFpsConfig.MIN_LOCK_FPS,
                ServerLockFpsConfig.setLockFps(ServerLockFpsConfig.MIN_LOCK_FPS));
        assertEquals(
                ServerLockFpsConfig.MAX_LOCK_FPS,
                ServerLockFpsConfig.setLockFps(ServerLockFpsConfig.MAX_LOCK_FPS));
    }

    // -------- applyServerLockFps() --------

    @Test
    void applyServerLockFpsRecordsAndForwardsVanillaValue() {
        ServerLockFpsConfig.applyServerLockFps(ServerLockFpsConfig.DEFAULT_LOCK_FPS);
        assertEquals(ServerLockFpsConfig.DEFAULT_LOCK_FPS, ServerLockFpsConfig.getCurrentLockFps());
        assertEquals(ServerLockFpsConfig.DEFAULT_LOCK_FPS, PerformanceSettings.getLockFPS());
    }

    @Test
    void applyServerLockFpsRecordsAndForwardsAnyValue() {
        ServerLockFpsConfig.applyServerLockFps(60);
        assertEquals(60, ServerLockFpsConfig.getCurrentLockFps());
        assertEquals(60, PerformanceSettings.getLockFPS());
    }

    @Test
    void applyServerLockFpsClampsBelowMinimum() {
        ServerLockFpsConfig.applyServerLockFps(-3);
        assertEquals(ServerLockFpsConfig.MIN_LOCK_FPS, ServerLockFpsConfig.getCurrentLockFps());
        assertEquals(ServerLockFpsConfig.MIN_LOCK_FPS, PerformanceSettings.getLockFPS());
    }

    @Test
    void applyServerLockFpsClampsAboveMaximum() {
        ServerLockFpsConfig.applyServerLockFps(99_999);
        assertEquals(ServerLockFpsConfig.MAX_LOCK_FPS, ServerLockFpsConfig.getCurrentLockFps());
        assertEquals(ServerLockFpsConfig.MAX_LOCK_FPS, PerformanceSettings.getLockFPS());
    }
}
