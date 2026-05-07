package io.pzstorm.storm.patch.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.core.PerformanceSettings;

/**
 * Verifies the server-side {@code lockFps} controller's property resolution, clamping, and live
 * setter (which propagates to {@link PerformanceSettings#setLockFPS(int)}).
 */
class ServerLockFpsConfigTest implements UnitTest {

    private String savedProperty;
    private int savedConfigValue;
    private int savedPerfSettings;

    @BeforeEach
    void captureState() {
        savedProperty = System.getProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY);
        System.clearProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY);
        savedConfigValue = ServerLockFpsConfig.getCurrentLockFps();
        savedPerfSettings = PerformanceSettings.getLockFPS();
        ServerLockFpsConfig.setCurrentLockFpsForTest(ServerLockFpsConfig.DEFAULT_LOCK_FPS);
    }

    @AfterEach
    void restoreState() {
        if (savedProperty == null) {
            System.clearProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY);
        } else {
            System.setProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY, savedProperty);
        }
        ServerLockFpsConfig.setCurrentLockFpsForTest(savedConfigValue);
        PerformanceSettings.setLockFPS(savedPerfSettings);
    }

    // -------- resolveLockFps() --------

    @Test
    void resolveReturnsDefaultWhenPropertyUnset() {
        assertEquals(ServerLockFpsConfig.DEFAULT_LOCK_FPS, ServerLockFpsConfig.resolveLockFps());
    }

    @Test
    void resolveReturnsConfiguredValueWhenInRange() {
        System.setProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY, "30");
        assertEquals(30, ServerLockFpsConfig.resolveLockFps());
    }

    @Test
    void resolveTrimsWhitespace() {
        System.setProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY, "  20  ");
        assertEquals(20, ServerLockFpsConfig.resolveLockFps());
    }

    @Test
    void resolveClampsBelowMinimum() {
        System.setProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY, "-5");
        assertEquals(ServerLockFpsConfig.MIN_LOCK_FPS, ServerLockFpsConfig.resolveLockFps());
    }

    @Test
    void resolveClampsAboveMaximum() {
        System.setProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY, "9999");
        assertEquals(ServerLockFpsConfig.MAX_LOCK_FPS, ServerLockFpsConfig.resolveLockFps());
    }

    @Test
    void resolveFallsBackOnNonNumeric() {
        System.setProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY, "fast");
        assertEquals(ServerLockFpsConfig.DEFAULT_LOCK_FPS, ServerLockFpsConfig.resolveLockFps());
    }

    @Test
    void resolveFallsBackOnEmptyString() {
        System.setProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY, "");
        assertEquals(ServerLockFpsConfig.DEFAULT_LOCK_FPS, ServerLockFpsConfig.resolveLockFps());
    }

    @Test
    void resolveAcceptsBoundaryValues() {
        System.setProperty(
                ServerLockFpsConfig.LOCK_FPS_PROPERTY,
                Integer.toString(ServerLockFpsConfig.MIN_LOCK_FPS));
        assertEquals(ServerLockFpsConfig.MIN_LOCK_FPS, ServerLockFpsConfig.resolveLockFps());

        System.setProperty(
                ServerLockFpsConfig.LOCK_FPS_PROPERTY,
                Integer.toString(ServerLockFpsConfig.MAX_LOCK_FPS));
        assertEquals(ServerLockFpsConfig.MAX_LOCK_FPS, ServerLockFpsConfig.resolveLockFps());
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
    void applyServerLockFpsUsesPropertyWhenCalledWithDefault() {
        System.setProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY, "45");
        ServerLockFpsConfig.applyServerLockFps(ServerLockFpsConfig.DEFAULT_LOCK_FPS);
        assertEquals(45, ServerLockFpsConfig.getCurrentLockFps());
        assertEquals(45, PerformanceSettings.getLockFPS());
    }

    @Test
    void applyServerLockFpsPassesThroughNonDefaultArg() {
        // A non-vanilla arg means the substitution caught a different setLockFPS site —
        // forward unchanged so we don't silently rewrite future game updates.
        System.setProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY, "45");
        ServerLockFpsConfig.applyServerLockFps(60);
        assertEquals(60, PerformanceSettings.getLockFPS());
        // currentLockFps stays at the test default (10) — pass-through doesn't update it.
        assertEquals(ServerLockFpsConfig.DEFAULT_LOCK_FPS, ServerLockFpsConfig.getCurrentLockFps());
    }

    @Test
    void applyServerLockFpsKeepsVanillaWhenPropertyUnset() {
        ServerLockFpsConfig.applyServerLockFps(ServerLockFpsConfig.DEFAULT_LOCK_FPS);
        assertEquals(ServerLockFpsConfig.DEFAULT_LOCK_FPS, ServerLockFpsConfig.getCurrentLockFps());
        assertEquals(ServerLockFpsConfig.DEFAULT_LOCK_FPS, PerformanceSettings.getLockFPS());
    }
}
