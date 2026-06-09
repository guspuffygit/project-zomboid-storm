package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import zombie.core.PerformanceSettings;
import zombie.network.GameServer;

/**
 * Server-side override for the FPS scalar used by {@code IsoPhysicsObject.update()}. Vanilla code
 * reads {@code GameServer.server ? 10 : PerformanceSettings.getLockFPS()} — the {@code 10} is a
 * literal hard-coded under the assumption that the server ticks at 10 TPS. With Storm's tick-rate
 * patch active, that constant becomes a drift source: the physics step continues to scale {@code
 * fpsMod = 30 / 10} regardless of the actual tick rate.
 *
 * <p>{@link IsoPhysicsObjectFpsPatch} substitutes the {@code GameServer.server} field read in
 * {@code IsoPhysicsObject.update()} with {@link #alwaysFalse()} (collapsing the ternary to its
 * second branch) and the {@code PerformanceSettings.getLockFPS()} call in the same method with
 * {@link #resolveFps()}. The resolver returns the configured value on the server and delegates to
 * the vanilla {@code lockFps} on the client, preserving client-side behavior.
 *
 * <p>The live value is mutable via {@link #setPhysicsFps(int)}, called only from {@link
 * io.pzstorm.storm.patch.networking.ServerFpsConfig#applyUnifiedFps(int)} — there is no direct
 * physics-fps HTTP endpoint.
 */
public final class IsoPhysicsObjectFpsConfig {

    /** Vanilla — {@code IsoPhysicsObject.update} hard-codes 10 on server. */
    public static final int DEFAULT_PHYSICS_FPS = 10;

    public static final int MIN_PHYSICS_FPS = 1;

    public static final int MAX_PHYSICS_FPS = 240;

    private static volatile int currentPhysicsFps = DEFAULT_PHYSICS_FPS;

    private IsoPhysicsObjectFpsConfig() {}

    /** Current effective server-side physics fps. */
    public static int getCurrentPhysicsFps() {
        return currentPhysicsFps;
    }

    /**
     * Live-updates the server-side physics fps, clamping to {@link #MIN_PHYSICS_FPS}..{@link
     * #MAX_PHYSICS_FPS}. Returns the value actually applied.
     */
    public static int setPhysicsFps(int requested) {
        int applied = clamp(requested);
        currentPhysicsFps = applied;
        StormPerformanceSandboxMetrics.setIsoPhysicsServerFps(applied);
        LOGGER.info("Storm: IsoPhysicsObject server fps updated to {}", applied);
        return applied;
    }

    /**
     * Substitution target for the {@code GameServer.server} field read inside {@code
     * IsoPhysicsObject.update()}. Always returns {@code false} so the original ternary collapses to
     * its second branch (the {@code getLockFPS()} call), which is also substituted.
     */
    public static boolean alwaysFalse() {
        return false;
    }

    /**
     * Substitution target for the {@code PerformanceSettings.getLockFPS()} call inside {@code
     * IsoPhysicsObject.update()}. On the server, returns the configured physics fps; on the client,
     * delegates to {@link PerformanceSettings#getLockFPS()} for vanilla behavior.
     */
    public static int resolveFps() {
        if (GameServer.server) {
            return currentPhysicsFps;
        }
        return PerformanceSettings.getLockFPS();
    }

    private static int clamp(int requested) {
        if (requested < MIN_PHYSICS_FPS) {
            LOGGER.warn(
                    "Storm: IsoPhysicsObject server fps {} below floor, clamping to {}",
                    requested,
                    MIN_PHYSICS_FPS);
            return MIN_PHYSICS_FPS;
        }
        if (requested > MAX_PHYSICS_FPS) {
            LOGGER.warn(
                    "Storm: IsoPhysicsObject server fps {} above ceiling, clamping to {}",
                    requested,
                    MAX_PHYSICS_FPS);
            return MAX_PHYSICS_FPS;
        }
        return requested;
    }

    /** Test-only — overrides the current value without clamping or logging. */
    public static void setCurrentPhysicsFpsForTest(int value) {
        currentPhysicsFps = value;
    }
}
