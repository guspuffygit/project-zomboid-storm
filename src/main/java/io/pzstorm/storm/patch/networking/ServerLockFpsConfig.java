package io.pzstorm.storm.patch.networking;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import io.pzstorm.storm.sandbox.StormPerformanceSandboxApplier;
import zombie.core.PerformanceSettings;
import zombie.network.GameServer;

/**
 * Server-side override for the {@code PerformanceSettings.setLockFPS(int)} call hard-coded to
 * {@code 10} inside {@code GameServer.main()}. {@link GameServerLockFpsPatch} substitutes that call
 * site with {@link #applyServerLockFps(int)}, which records the value and forwards it through the
 * same vanilla setter. The {@code Storm.ServerFps} sandbox option then overrides this at {@code
 * OnServerStarted} via {@link ServerFpsConfig#applyUnifiedFps(int)} → {@link #setLockFps(int)}.
 *
 * <p>The live value is mutable via {@link #setLockFps(int)}, called only from {@link
 * ServerFpsConfig#applyUnifiedFps(int)}.
 *
 * <p>Note: this knob only changes what {@code PerformanceSettings.getLockFPS()} reports — the
 * actual server tick rate is governed by {@link GameServerTickRatePatch}. The unified {@link
 * ServerFpsConfig} keeps both aligned.
 */
public final class ServerLockFpsConfig {

    /** Vanilla server lockFps — {@code GameServer.main} hard-codes this. */
    public static final int DEFAULT_LOCK_FPS = 10;

    public static final int MIN_LOCK_FPS = 1;

    public static final int MAX_LOCK_FPS = 240;

    private static volatile int currentLockFps = DEFAULT_LOCK_FPS;

    private ServerLockFpsConfig() {}

    /**
     * Substitution target for the {@code PerformanceSettings.setLockFPS(int)} call inside {@code
     * GameServer.main}. Records the vanilla value, forwards it through the same vanilla setter, and
     * then re-applies the {@code Storm.ServerFps} sandbox option so the sandbox value overrides the
     * vanilla baseline.
     *
     * <p>This is the boot-time apply seam for fps controllers. {@code OnServerStartedEvent} fires
     * inside {@code GameServer.startServer()} at line 1513, BEFORE the patched {@code new
     * UpdateLimit(100L)} at {@code GameServer.main()} line 822 installs the tick limiter and BEFORE
     * the patched {@code PerformanceSettings.setLockFPS(10)} at line 823 records the vanilla
     * baseline. By the time this method runs, both vanilla setups are complete, so the sandbox
     * applier can safely apply {@code Storm.ServerFps} without the vanilla {@code setLockFPS(10)}
     * call overwriting it afterwards.
     */
    public static void applyServerLockFps(int vanillaValue) {
        int applied = clamp(vanillaValue);
        currentLockFps = applied;
        PerformanceSettings.setLockFPS(applied);
        StormPerformanceSandboxMetrics.setServerLockFps(applied);
        if (GameServer.server) {
            StormPerformanceSandboxApplier.applyServerFps();
        }
    }

    /** Current effective server lockFps. */
    public static int getCurrentLockFps() {
        return currentLockFps;
    }

    /**
     * Live-updates the server lockFps, clamping to {@link #MIN_LOCK_FPS}..{@link #MAX_LOCK_FPS} and
     * propagating to {@link PerformanceSettings#setLockFPS(int)}. Returns the value actually
     * applied.
     */
    public static int setLockFps(int requested) {
        int applied = clamp(requested);
        currentLockFps = applied;
        PerformanceSettings.setLockFPS(applied);
        StormPerformanceSandboxMetrics.setServerLockFps(applied);
        LOGGER.info("Storm: server lockFps updated to {}", applied);
        return applied;
    }

    private static int clamp(int requested) {
        if (requested < MIN_LOCK_FPS) {
            LOGGER.warn(
                    "Storm: server lockFps {} below floor, clamping to {}",
                    requested,
                    MIN_LOCK_FPS);
            return MIN_LOCK_FPS;
        }
        if (requested > MAX_LOCK_FPS) {
            LOGGER.warn(
                    "Storm: server lockFps {} above ceiling, clamping to {}",
                    requested,
                    MAX_LOCK_FPS);
            return MAX_LOCK_FPS;
        }
        return requested;
    }

    /** Test-only — overrides the cached value without clamping or logging. */
    static void setCurrentLockFpsForTest(int value) {
        currentLockFps = value;
    }
}
