package io.pzstorm.storm.patch.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.patch.networking.GameServerTickRatePatch.UpdateLimitFactory;
import io.pzstorm.storm.patch.performance.IsoPhysicsObjectFpsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.core.PerformanceSettings;
import zombie.core.utils.UpdateLimit;

/**
 * Verifies the unified server-fps controller's math (fps↔tickIntervalMs), system-property
 * resolution, and that {@link ServerFpsConfig#applyUnifiedFps(int)} propagates to all three
 * subordinate controllers ({@link UpdateLimitFactory}, {@link ServerLockFpsConfig}, {@link
 * IsoPhysicsObjectFpsConfig}). Also covers each subordinate resolver's fallback to {@link
 * ServerFpsConfig#SERVER_FPS_PROPERTY} when its specific property is unset.
 */
class ServerFpsConfigTest implements UnitTest {

    private String savedUnifiedProperty;
    private String savedTickProperty;
    private String savedLockProperty;
    private String savedPhysicsProperty;
    private int savedLockConfigValue;
    private int savedPhysicsConfigValue;
    private int savedPerfSettings;
    private long savedLogWindow;

    @BeforeEach
    void captureState() {
        savedUnifiedProperty = System.getProperty(ServerFpsConfig.SERVER_FPS_PROPERTY);
        savedTickProperty = System.getProperty(GameServerTickRatePatch.TICK_INTERVAL_PROPERTY);
        savedLockProperty = System.getProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY);
        savedPhysicsProperty = System.getProperty(IsoPhysicsObjectFpsConfig.PHYSICS_FPS_PROPERTY);
        System.clearProperty(ServerFpsConfig.SERVER_FPS_PROPERTY);
        System.clearProperty(GameServerTickRatePatch.TICK_INTERVAL_PROPERTY);
        System.clearProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY);
        System.clearProperty(IsoPhysicsObjectFpsConfig.PHYSICS_FPS_PROPERTY);

        savedLockConfigValue = ServerLockFpsConfig.getCurrentLockFps();
        savedPhysicsConfigValue = IsoPhysicsObjectFpsConfig.getCurrentPhysicsFps();
        savedPerfSettings = PerformanceSettings.getLockFPS();
        savedLogWindow = UpdateLimitFactory.logWindowNanos;

        ServerLockFpsConfig.setCurrentLockFpsForTest(ServerLockFpsConfig.DEFAULT_LOCK_FPS);
        IsoPhysicsObjectFpsConfig.setCurrentPhysicsFpsForTest(
                IsoPhysicsObjectFpsConfig.DEFAULT_PHYSICS_FPS);
        UpdateLimitFactory.logWindowNanos = Long.MAX_VALUE;
        UpdateLimitFactory.resetTickCounterForTest();
        UpdateLimitFactory.clearServerTickLimiterForTest();
    }

    @AfterEach
    void restoreState() {
        restore(ServerFpsConfig.SERVER_FPS_PROPERTY, savedUnifiedProperty);
        restore(GameServerTickRatePatch.TICK_INTERVAL_PROPERTY, savedTickProperty);
        restore(ServerLockFpsConfig.LOCK_FPS_PROPERTY, savedLockProperty);
        restore(IsoPhysicsObjectFpsConfig.PHYSICS_FPS_PROPERTY, savedPhysicsProperty);

        ServerLockFpsConfig.setCurrentLockFpsForTest(savedLockConfigValue);
        IsoPhysicsObjectFpsConfig.setCurrentPhysicsFpsForTest(savedPhysicsConfigValue);
        PerformanceSettings.setLockFPS(savedPerfSettings);
        UpdateLimitFactory.logWindowNanos = savedLogWindow;
        UpdateLimitFactory.resetTickCounterForTest();
        UpdateLimitFactory.clearServerTickLimiterForTest();
    }

    private static void restore(String key, String saved) {
        if (saved == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, saved);
        }
    }

    // -------- fpsToTickIntervalMs() math --------

    @Test
    void fpsToTickIntervalMs_vanilla10() {
        assertEquals(100L, ServerFpsConfig.fpsToTickIntervalMs(10));
    }

    @Test
    void fpsToTickIntervalMs_20() {
        assertEquals(50L, ServerFpsConfig.fpsToTickIntervalMs(20));
    }

    @Test
    void fpsToTickIntervalMs_30RoundsTo33() {
        // 1000 / 30 = 33.333... → round = 33
        assertEquals(33L, ServerFpsConfig.fpsToTickIntervalMs(30));
    }

    @Test
    void fpsToTickIntervalMs_60RoundsTo17() {
        // 1000 / 60 = 16.666... → round = 17
        assertEquals(17L, ServerFpsConfig.fpsToTickIntervalMs(60));
    }

    @Test
    void fpsToTickIntervalMs_100() {
        assertEquals(10L, ServerFpsConfig.fpsToTickIntervalMs(100));
    }

    @Test
    void fpsToTickIntervalMs_1HitsMaxInterval() {
        // 1000 / 1 = 1000ms — exactly MAX_TICK_INTERVAL_MS.
        assertEquals(1000L, ServerFpsConfig.fpsToTickIntervalMs(1));
    }

    @Test
    void fpsToTickIntervalMs_240RoundsTo4() {
        // 1000 / 240 = 4.166... → round = 4
        assertEquals(4L, ServerFpsConfig.fpsToTickIntervalMs(240));
    }

    @Test
    void fpsToTickIntervalMs_zeroIsUnbounded() {
        assertEquals(0L, ServerFpsConfig.fpsToTickIntervalMs(0));
    }

    @Test
    void fpsToTickIntervalMs_negativeIsUnbounded() {
        assertEquals(0L, ServerFpsConfig.fpsToTickIntervalMs(-5));
    }

    @Test
    void fpsToTickIntervalMs_veryHighFpsClampsToMin1Ms() {
        // 1000 / 2000 = 0.5 → round = 1 (Math.max(1, ...) floor).
        assertEquals(1L, ServerFpsConfig.fpsToTickIntervalMs(2000));
    }

    // -------- tickIntervalMsToFps() math (inverse) --------

    @Test
    void tickIntervalMsToFps_vanilla100Returns10() {
        assertEquals(10, ServerFpsConfig.tickIntervalMsToFps(100L));
    }

    @Test
    void tickIntervalMsToFps_50Returns20() {
        assertEquals(20, ServerFpsConfig.tickIntervalMsToFps(50L));
    }

    @Test
    void tickIntervalMsToFps_33Returns30() {
        // 1000 / 33 = 30.303... → round = 30
        assertEquals(30, ServerFpsConfig.tickIntervalMsToFps(33L));
    }

    @Test
    void tickIntervalMsToFps_17Returns59NotRoundtrippableWith60() {
        // Both directions round, so fps=60 → 17ms but 17ms → 59 (not 60).
        assertEquals(59, ServerFpsConfig.tickIntervalMsToFps(17L));
    }

    @Test
    void tickIntervalMsToFps_zeroIsUnboundedSentinel() {
        assertEquals(0, ServerFpsConfig.tickIntervalMsToFps(0L));
    }

    @Test
    void tickIntervalMsToFps_negativeIsUnboundedSentinel() {
        assertEquals(0, ServerFpsConfig.tickIntervalMsToFps(-1L));
    }

    @Test
    void tickIntervalMsToFps_1000Returns1() {
        assertEquals(1, ServerFpsConfig.tickIntervalMsToFps(1000L));
    }

    // -------- resolveUnifiedFps() --------

    @Test
    void resolveUnifiedFpsReturnsUnresolvedWhenUnset() {
        assertEquals(ServerFpsConfig.UNRESOLVED, ServerFpsConfig.resolveUnifiedFps());
    }

    @Test
    void resolveUnifiedFpsReturnsConfiguredValue() {
        System.setProperty(ServerFpsConfig.SERVER_FPS_PROPERTY, "30");
        assertEquals(30, ServerFpsConfig.resolveUnifiedFps());
    }

    @Test
    void resolveUnifiedFpsTrimsWhitespace() {
        System.setProperty(ServerFpsConfig.SERVER_FPS_PROPERTY, "  60  ");
        assertEquals(60, ServerFpsConfig.resolveUnifiedFps());
    }

    @Test
    void resolveUnifiedFpsClampsBelowMin() {
        System.setProperty(ServerFpsConfig.SERVER_FPS_PROPERTY, "0");
        assertEquals(ServerFpsConfig.MIN_FPS, ServerFpsConfig.resolveUnifiedFps());
    }

    @Test
    void resolveUnifiedFpsClampsAboveMax() {
        System.setProperty(ServerFpsConfig.SERVER_FPS_PROPERTY, "9999");
        assertEquals(ServerFpsConfig.MAX_FPS, ServerFpsConfig.resolveUnifiedFps());
    }

    @Test
    void resolveUnifiedFpsReturnsUnresolvedOnNonNumeric() {
        System.setProperty(ServerFpsConfig.SERVER_FPS_PROPERTY, "fast");
        assertEquals(ServerFpsConfig.UNRESOLVED, ServerFpsConfig.resolveUnifiedFps());
    }

    @Test
    void resolveUnifiedFpsReturnsUnresolvedOnEmpty() {
        System.setProperty(ServerFpsConfig.SERVER_FPS_PROPERTY, "");
        assertEquals(ServerFpsConfig.UNRESOLVED, ServerFpsConfig.resolveUnifiedFps());
    }

    // -------- applyUnifiedFps() --------

    @Test
    void applyUnifiedFpsThrowsWhenLimiterNotInstalled() {
        assertThrows(IllegalStateException.class, () -> ServerFpsConfig.applyUnifiedFps(30));
    }

    @Test
    void applyUnifiedFpsPropagatesToAllThreeKnobs() {
        UpdateLimit limit = UpdateLimitFactory.create(100L);

        ServerFpsConfig.AppliedFps applied = ServerFpsConfig.applyUnifiedFps(30);

        assertEquals(30, applied.requestedFps());
        assertEquals(30, applied.appliedFps());
        assertEquals(33L, applied.appliedTickIntervalMs());
        assertEquals(30, applied.appliedLockFps());
        assertEquals(30, applied.appliedPhysicsFps());

        // Verify the live state of the three subordinate controllers reflects the unified update.
        assertEquals(33L, limit.getDelay());
        assertEquals(33L, UpdateLimitFactory.getCurrentTickIntervalMs());
        assertEquals(30, ServerLockFpsConfig.getCurrentLockFps());
        assertEquals(30, PerformanceSettings.getLockFPS());
        assertEquals(30, IsoPhysicsObjectFpsConfig.getCurrentPhysicsFps());
    }

    @Test
    void applyUnifiedFpsClampsBelowMin() {
        UpdateLimitFactory.create(100L);

        ServerFpsConfig.AppliedFps applied = ServerFpsConfig.applyUnifiedFps(-5);

        assertEquals(-5, applied.requestedFps());
        assertEquals(ServerFpsConfig.MIN_FPS, applied.appliedFps());
        assertEquals(1000L, applied.appliedTickIntervalMs());
        assertEquals(ServerFpsConfig.MIN_FPS, applied.appliedLockFps());
        assertEquals(ServerFpsConfig.MIN_FPS, applied.appliedPhysicsFps());
    }

    @Test
    void applyUnifiedFpsClampsAboveMax() {
        UpdateLimitFactory.create(100L);

        ServerFpsConfig.AppliedFps applied = ServerFpsConfig.applyUnifiedFps(9999);

        assertEquals(9999, applied.requestedFps());
        assertEquals(ServerFpsConfig.MAX_FPS, applied.appliedFps());
        assertEquals(4L, applied.appliedTickIntervalMs());
        assertEquals(ServerFpsConfig.MAX_FPS, applied.appliedLockFps());
        assertEquals(ServerFpsConfig.MAX_FPS, applied.appliedPhysicsFps());
    }

    // -------- subordinate resolver fallback to unified property --------

    @Test
    void tickRateResolverFallsBackToUnifiedFps() {
        System.setProperty(ServerFpsConfig.SERVER_FPS_PROPERTY, "30");
        assertEquals(33L, UpdateLimitFactory.resolveTickIntervalMs());
    }

    @Test
    void tickRateResolverPrefersSpecificPropertyOverUnified() {
        System.setProperty(ServerFpsConfig.SERVER_FPS_PROPERTY, "30");
        System.setProperty(GameServerTickRatePatch.TICK_INTERVAL_PROPERTY, "50");
        assertEquals(50L, UpdateLimitFactory.resolveTickIntervalMs());
    }

    @Test
    void lockFpsResolverFallsBackToUnifiedFps() {
        System.setProperty(ServerFpsConfig.SERVER_FPS_PROPERTY, "60");
        assertEquals(60, ServerLockFpsConfig.resolveLockFps());
    }

    @Test
    void lockFpsResolverPrefersSpecificPropertyOverUnified() {
        System.setProperty(ServerFpsConfig.SERVER_FPS_PROPERTY, "60");
        System.setProperty(ServerLockFpsConfig.LOCK_FPS_PROPERTY, "20");
        assertEquals(20, ServerLockFpsConfig.resolveLockFps());
    }

    @Test
    void physicsFpsResolverFallsBackToUnifiedFps() {
        System.setProperty(ServerFpsConfig.SERVER_FPS_PROPERTY, "45");
        assertEquals(45, IsoPhysicsObjectFpsConfig.resolvePhysicsFps());
    }

    @Test
    void physicsFpsResolverPrefersSpecificPropertyOverUnified() {
        System.setProperty(ServerFpsConfig.SERVER_FPS_PROPERTY, "45");
        System.setProperty(IsoPhysicsObjectFpsConfig.PHYSICS_FPS_PROPERTY, "15");
        assertEquals(15, IsoPhysicsObjectFpsConfig.resolvePhysicsFps());
    }

    @Test
    void resolversFallBackToOwnDefaultWhenNothingSet() {
        assertEquals(
                GameServerTickRatePatch.DEFAULT_TICK_INTERVAL_MS,
                UpdateLimitFactory.resolveTickIntervalMs());
        assertEquals(ServerLockFpsConfig.DEFAULT_LOCK_FPS, ServerLockFpsConfig.resolveLockFps());
        assertEquals(
                IsoPhysicsObjectFpsConfig.DEFAULT_PHYSICS_FPS,
                IsoPhysicsObjectFpsConfig.resolvePhysicsFps());
    }
}
