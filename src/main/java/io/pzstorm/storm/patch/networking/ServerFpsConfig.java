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
 * <p>At startup, {@link #SERVER_FPS_PROPERTY} acts as a fallback default for each subordinate
 * controller — the controller's specific property still wins when both are set, so per-knob
 * overrides remain available. At runtime, the {@code POST /storm/server/fps} HTTP endpoint applies
 * a fresh fps to all three controllers via {@link #applyUnifiedFps(int)}.
 *
 * <p>Range: {@link #MIN_FPS}..{@link #MAX_FPS}. fps below 1 isn't supported by the subordinate
 * controllers (their floors are 1); for unbounded ticking, set {@code
 * -Dstorm.server.tickIntervalMs=0} directly.
 */
public final class ServerFpsConfig {

    public static final String SERVER_FPS_PROPERTY = "storm.server.fps";

    public static final int MIN_FPS = 1;

    public static final int MAX_FPS = 240;

    /**
     * Sentinel returned by {@link #resolveUnifiedFps()} when the property is unset or unparseable.
     */
    public static final int UNRESOLVED = -1;

    private ServerFpsConfig() {}

    /**
     * Maps a target fps to the corresponding tick interval in ms. fps ≤ 0 returns {@code 0L}
     * (unbounded — UpdateLimit gating disabled). For fps ≥ 1, returns {@code max(1, round(1000 /
     * fps))}.
     */
    public static long fpsToTickIntervalMs(int fps) {
        if (fps <= 0) {
            return 0L;
        }
        return Math.max(1L, Math.round(1000.0 / fps));
    }

    /**
     * Inverse of {@link #fpsToTickIntervalMs(int)}. {@code 0ms} returns {@code 0} (sentinel for
     * unbounded). For intervalMs ≥ 1, returns {@code round(1000 / intervalMs)}. Note: not strictly
     * round-trippable for values where {@code 1000 / fps} isn't an integer (e.g. fps=30 → 33ms →
     * 30, but fps=60 → 17ms → 59), because both directions round.
     */
    public static int tickIntervalMsToFps(long intervalMs) {
        if (intervalMs <= 0) {
            return 0;
        }
        return (int) Math.round(1000.0 / intervalMs);
    }

    /**
     * Reads {@link #SERVER_FPS_PROPERTY}, clamping to {@link #MIN_FPS}..{@link #MAX_FPS}. Returns
     * {@link #UNRESOLVED} when the property is unset, empty, or unparseable.
     */
    public static int resolveUnifiedFps() {
        String prop = System.getProperty(SERVER_FPS_PROPERTY);
        if (prop == null || prop.isEmpty()) {
            return UNRESOLVED;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(prop.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn(
                    "Storm: invalid -D{}=\"{}\", ignoring unified fps fallback",
                    SERVER_FPS_PROPERTY,
                    prop);
            return UNRESOLVED;
        }
        return clamp(parsed);
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
