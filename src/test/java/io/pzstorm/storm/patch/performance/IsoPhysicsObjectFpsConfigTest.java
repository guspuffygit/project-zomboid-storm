package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.core.PerformanceSettings;
import zombie.network.GameServer;

/**
 * Verifies the IsoPhysicsObject server-fps controller's clamping, live setter, and substitution
 * helpers. {@link IsoPhysicsObjectFpsConfig#alwaysFalse()} must always return {@code false} so the
 * patched ternary in {@code IsoPhysicsObject.update()} collapses to its second branch; {@link
 * IsoPhysicsObjectFpsConfig#resolveFps()} must return the configured value on the server and the
 * vanilla {@code lockFps} on the client.
 */
class IsoPhysicsObjectFpsConfigTest implements UnitTest {

    private int savedConfigValue;
    private int savedPerfSettings;
    private boolean savedServerFlag;

    @BeforeEach
    void captureState() {
        savedConfigValue = IsoPhysicsObjectFpsConfig.getCurrentPhysicsFps();
        savedPerfSettings = PerformanceSettings.getLockFPS();
        savedServerFlag = GameServer.server;
        IsoPhysicsObjectFpsConfig.setCurrentPhysicsFpsForTest(
                IsoPhysicsObjectFpsConfig.DEFAULT_PHYSICS_FPS);
    }

    @AfterEach
    void restoreState() {
        IsoPhysicsObjectFpsConfig.setCurrentPhysicsFpsForTest(savedConfigValue);
        PerformanceSettings.setLockFPS(savedPerfSettings);
        GameServer.server = savedServerFlag;
    }

    // -------- setPhysicsFps() --------

    @Test
    void setPhysicsFpsAppliesAndPersists() {
        int applied = IsoPhysicsObjectFpsConfig.setPhysicsFps(30);
        assertEquals(30, applied);
        assertEquals(30, IsoPhysicsObjectFpsConfig.getCurrentPhysicsFps());
    }

    @Test
    void setPhysicsFpsClampsBelowMinimum() {
        int applied = IsoPhysicsObjectFpsConfig.setPhysicsFps(-3);
        assertEquals(IsoPhysicsObjectFpsConfig.MIN_PHYSICS_FPS, applied);
        assertEquals(
                IsoPhysicsObjectFpsConfig.MIN_PHYSICS_FPS,
                IsoPhysicsObjectFpsConfig.getCurrentPhysicsFps());
    }

    @Test
    void setPhysicsFpsClampsAboveMaximum() {
        int applied = IsoPhysicsObjectFpsConfig.setPhysicsFps(99_999);
        assertEquals(IsoPhysicsObjectFpsConfig.MAX_PHYSICS_FPS, applied);
        assertEquals(
                IsoPhysicsObjectFpsConfig.MAX_PHYSICS_FPS,
                IsoPhysicsObjectFpsConfig.getCurrentPhysicsFps());
    }

    @Test
    void setPhysicsFpsAcceptsBoundaryValues() {
        assertEquals(
                IsoPhysicsObjectFpsConfig.MIN_PHYSICS_FPS,
                IsoPhysicsObjectFpsConfig.setPhysicsFps(IsoPhysicsObjectFpsConfig.MIN_PHYSICS_FPS));
        assertEquals(
                IsoPhysicsObjectFpsConfig.MAX_PHYSICS_FPS,
                IsoPhysicsObjectFpsConfig.setPhysicsFps(IsoPhysicsObjectFpsConfig.MAX_PHYSICS_FPS));
    }

    // -------- alwaysFalse() (substitution helper) --------

    @Test
    void alwaysFalseAlwaysReturnsFalse() {
        assertFalse(IsoPhysicsObjectFpsConfig.alwaysFalse());
    }

    // -------- resolveFps() (substitution helper) --------

    @Test
    void resolveFpsReturnsConfiguredValueOnServer() {
        GameServer.server = true;
        IsoPhysicsObjectFpsConfig.setPhysicsFps(40);
        assertEquals(40, IsoPhysicsObjectFpsConfig.resolveFps());
    }

    @Test
    void resolveFpsReturnsLockFpsOnClient() {
        GameServer.server = false;
        PerformanceSettings.setLockFPS(75);
        assertEquals(75, IsoPhysicsObjectFpsConfig.resolveFps());
    }

    @Test
    void resolveFpsTracksLiveServerUpdates() {
        GameServer.server = true;
        IsoPhysicsObjectFpsConfig.setPhysicsFps(20);
        assertEquals(20, IsoPhysicsObjectFpsConfig.resolveFps());
        IsoPhysicsObjectFpsConfig.setPhysicsFps(60);
        assertEquals(60, IsoPhysicsObjectFpsConfig.resolveFps());
    }
}
