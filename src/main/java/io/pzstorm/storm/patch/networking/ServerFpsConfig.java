package io.pzstorm.storm.patch.networking;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.patch.networking.GameServerTickRatePatch.UpdateLimitFactory;
import io.pzstorm.storm.patch.performance.IsoPhysicsObjectFpsConfig;

/**
 * Unified server fps knob. A single fps value drives all three subordinate controllers:
 *
 * <ul>
 *   <li>{@link UpdateLimitFactory} — server tick interval ({@code intervalMs = round(1000 / fps)})
 *   <li>{@link ServerLockFpsConfig} — value reported by {@code PerformanceSettings.getLockFPS()} on
 *       the server
 *   <li>{@link IsoPhysicsObjectFpsConfig} — fps divisor inside {@code IsoPhysicsObject.update()}
 * </ul>
 *
 * <p>The {@code Storm.ServerFps} sandbox option drives this at {@code OnServerStarted}; {@code POST
 * /storm/server/fps} retunes it at runtime. There are no subordinate HTTP endpoints — the three
 * controllers always move together.
 *
 * <p>Range: {@link #MIN_FPS}..{@link #MAX_FPS}.
 */
public final class ServerFpsConfig {

    public static final int MIN_FPS = 1;

    public static final int MAX_FPS = 240;

    private ServerFpsConfig() {}

    /**
     * Maps a target fps to the corresponding tick interval in ms: {@code max(1, round(1000 /
     * fps))}. Caller must pass {@code fps >= 1}; callers reach this only after {@link #clamp(int)}.
     */
    public static long fpsToTickIntervalMs(int fps) {
        return Math.max(1L, Math.round(1000.0 / fps));
    }

    /**
     * Inverse of {@link #fpsToTickIntervalMs(int)}: {@code round(1000 / intervalMs)}. Caller must
     * pass {@code intervalMs >= 1}. Note: not strictly round-trippable for values where {@code 1000
     * / fps} isn't an integer (e.g. fps=30 → 33ms → 30, but fps=60 → 17ms → 59), because both
     * directions round.
     */
    public static int tickIntervalMsToFps(long intervalMs) {
        return (int) Math.round(1000.0 / intervalMs);
    }

    /**
     * Live-updates all three subordinate knobs from a single fps value, clamping to {@link
     * #MIN_FPS}..{@link #MAX_FPS}. Throws {@link IllegalStateException} if the server tick limiter
     * has not been installed yet (i.e. before {@code GameServer.main} has run).
     */
    public static AppliedFps applyUnifiedFps(int requestedFps) {
        int clampedFps = clamp(requestedFps);
        long appliedInterval =
                UpdateLimitFactory.setTickIntervalMs(fpsToTickIntervalMs(clampedFps));
        int appliedLockFps = ServerLockFpsConfig.setLockFps(clampedFps);
        int appliedPhysicsFps = IsoPhysicsObjectFpsConfig.setPhysicsFps(clampedFps);
        LOGGER.info(
                "Storm: unified server fps applied: requested={} → tickIntervalMs={} lockFps={} physicsFps={}",
                requestedFps,
                appliedInterval,
                appliedLockFps,
                appliedPhysicsFps);
        return new AppliedFps(
                requestedFps, clampedFps, appliedInterval, appliedLockFps, appliedPhysicsFps);
    }

    private static int clamp(int requested) {
        if (requested < MIN_FPS) {
            LOGGER.warn(
                    "Storm: unified server fps {} below floor, clamping to {}", requested, MIN_FPS);
            return MIN_FPS;
        }
        if (requested > MAX_FPS) {
            LOGGER.warn(
                    "Storm: unified server fps {} above ceiling, clamping to {}",
                    requested,
                    MAX_FPS);
            return MAX_FPS;
        }
        return requested;
    }

    public record AppliedFps(
            int requestedFps,
            int appliedFps,
            long appliedTickIntervalMs,
            int appliedLockFps,
            int appliedPhysicsFps) {}
}
