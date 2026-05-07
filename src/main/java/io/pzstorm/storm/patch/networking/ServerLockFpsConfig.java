package io.pzstorm.storm.patch.networking;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import zombie.core.PerformanceSettings;

/**
 * Server-side override for the {@code PerformanceSettings.setLockFPS(int)} call hard-coded to
 * {@code 10} inside {@code GameServer.main()}. {@link GameServerLockFpsPatch} substitutes that call
 * site with {@link #applyServerLockFps(int)}, which reads the {@link #LOCK_FPS_PROPERTY} system
 * property and applies the resolved value through the same vanilla setter.
 *
 * <p>The default value is read from the system property at substitution time and clamped to {@link
 * #MIN_LOCK_FPS}..{@link #MAX_LOCK_FPS}. The live value is mutable via {@link #setLockFps(int)};
 * the {@code POST /storm/server/lockFps} HTTP endpoint exposes that setter at runtime.
 *
 * <p>Note: this knob only changes what {@code PerformanceSettings.getLockFPS()} reports — the
 * actual server tick rate is governed by {@link GameServerTickRatePatch}. Keep both aligned.
 */
public final class ServerLockFpsConfig {

    public static final String LOCK_FPS_PROPERTY = "storm.server.lockFps";

    /** Vanilla server lockFps — {@code GameServer.main} hard-codes this. */
    public static final int DEFAULT_LOCK_FPS = 10;

    public static final int MIN_LOCK_FPS = 1;

    public static final int MAX_LOCK_FPS = 240;

    private static volatile int currentLockFps = DEFAULT_LOCK_FPS;

    private ServerLockFpsConfig() {}

    /**
     * Substitution target for the {@code PerformanceSettings.setLockFPS(int)} call inside {@code
     * GameServer.main}. Resolves the configured value, applies it through the vanilla setter, and
     * caches it for the live getter.
     *
     * <p>If the substituted call passes a value other than {@link #DEFAULT_LOCK_FPS}, it is treated
     * as a non-targeted call (e.g. a future game update adds another {@code setLockFPS} site inside
     * {@code main}) and forwarded unchanged to preserve vanilla semantics.
     */
    public static void applyServerLockFps(int vanillaValue) {
        if (vanillaValue != DEFAULT_LOCK_FPS) {
            PerformanceSettings.setLockFPS(vanillaValue);
            return;
        }
        int resolved = resolveLockFps();
        if (resolved == DEFAULT_LOCK_FPS) {
            LOGGER.info("Storm: server lockFps = {} [vanilla]", resolved);
        } else {
            LOGGER.info(
                    "Storm: server lockFps = {} [override via -D{}]", resolved, LOCK_FPS_PROPERTY);
        }
        currentLockFps = resolved;
        PerformanceSettings.setLockFPS(resolved);
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
        LOGGER.info("Storm: server lockFps updated to {}", applied);
        return applied;
    }

    /**
     * Reads {@link #LOCK_FPS_PROPERTY}, clamping to {@link #MIN_LOCK_FPS}..{@link #MAX_LOCK_FPS}.
     * When the specific property is unset or unparseable, falls back to {@link
     * ServerFpsConfig#SERVER_FPS_PROPERTY} (the unified fps knob), and finally to {@link
     * #DEFAULT_LOCK_FPS}.
     */
    public static int resolveLockFps() {
        String prop = System.getProperty(LOCK_FPS_PROPERTY);
        if (prop == null || prop.isEmpty()) {
            return resolveFromUnifiedOrDefault();
        }
        int parsed;
        try {
            parsed = Integer.parseInt(prop.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn(
                    "Storm: invalid -D{}=\"{}\", falling back to default {}",
                    LOCK_FPS_PROPERTY,
                    prop,
                    DEFAULT_LOCK_FPS);
            return resolveFromUnifiedOrDefault();
        }
        return clamp(parsed);
    }

    private static int resolveFromUnifiedOrDefault() {
        int unifiedFps = ServerFpsConfig.resolveUnifiedFps();
        if (unifiedFps != ServerFpsConfig.UNRESOLVED) {
            return clamp(unifiedFps);
        }
        return DEFAULT_LOCK_FPS;
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
