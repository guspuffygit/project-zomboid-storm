package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import zombie.MovingObjectUpdateScheduler;

/**
 * Per-animal tick stride for {@code IsoAnimal.updateLOS()} on the dedicated server.
 *
 * <p>An animal runs {@code updateLOS} only on ticks where {@code (frameCounter - animalId) mod
 * tickInterval == 0}, distributing animals evenly across the cycle so that on any given tick only
 * {@code ~1/tickInterval} of all animals scan their LOS. Default {@link #DEFAULT_TICK_INTERVAL} =
 * 1, which preserves vanilla every-tick behavior.
 *
 * <p>The default value is read once from the {@link #TICK_INTERVAL_PROPERTY} system property at
 * class load and clamped to {@link #MIN_TICK_INTERVAL}..{@link #MAX_TICK_INTERVAL}. The live value
 * is mutable via {@link #setTickInterval(int)}; the {@code POST /storm/animalLOS/tickInterval} HTTP
 * endpoint exposes that setter at runtime.
 */
public final class AnimalLOSTickInterval {

    public static final String TICK_INTERVAL_PROPERTY = "storm.animalLOS.tickInterval";

    /** Vanilla — every animal runs LOS every tick. */
    public static final int DEFAULT_TICK_INTERVAL = 1;

    /** 0 disables LOS entirely; 1 = vanilla every-tick. */
    public static final int MIN_TICK_INTERVAL = 0;

    /** Hard ceiling — at 64, an animal updates LOS roughly every 6 seconds at 10 TPS. */
    public static final int MAX_TICK_INTERVAL = 64;

    private static volatile int currentTickInterval = initialize();

    private AnimalLOSTickInterval() {}

    private static int initialize() {
        int resolved = resolveTickInterval();
        if (resolved == DEFAULT_TICK_INTERVAL) {
            LOGGER.info("Storm: animal-LOS tick interval = {} [vanilla every-tick]", resolved);
        } else {
            LOGGER.info(
                    "Storm: animal-LOS tick interval = {} [override via -D{}]",
                    resolved,
                    TICK_INTERVAL_PROPERTY);
        }
        return resolved;
    }

    /** Current effective tick interval. */
    public static int getCurrentTickInterval() {
        return currentTickInterval;
    }

    /**
     * Live-updates the per-animal tick interval, clamping to {@link #MIN_TICK_INTERVAL}..{@link
     * #MAX_TICK_INTERVAL}. Returns the value actually applied.
     */
    public static int setTickInterval(int requested) {
        int applied = clamp(requested);
        currentTickInterval = applied;
        LOGGER.info("Storm: animal-LOS tick interval updated to {}", applied);
        return applied;
    }

    /**
     * Reads {@link #TICK_INTERVAL_PROPERTY}, clamping to {@link #MIN_TICK_INTERVAL}..{@link
     * #MAX_TICK_INTERVAL}. Returns {@link #DEFAULT_TICK_INTERVAL} when the property is unset or
     * unparseable.
     */
    public static int resolveTickInterval() {
        String prop = System.getProperty(TICK_INTERVAL_PROPERTY);
        if (prop == null || prop.isEmpty()) {
            return DEFAULT_TICK_INTERVAL;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(prop.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn(
                    "Storm: invalid -D{}=\"{}\", falling back to default {}",
                    TICK_INTERVAL_PROPERTY,
                    prop,
                    DEFAULT_TICK_INTERVAL);
            return DEFAULT_TICK_INTERVAL;
        }
        return clamp(parsed);
    }

    /**
     * Pure, deterministic predicate. Returns {@code true} when an animal with id {@code animalId}
     * should run {@code updateLOS} at frame {@code frameCounter} given the configured {@code
     * tickInterval}.
     *
     * <p>Stride 0 always returns false (LOS disabled). Stride 1 always returns true (vanilla). For
     * stride &gt; 1, the result is {@code (frameCounter - animalId) mod tickInterval == 0},
     * computed with {@link Math#floorMod(long, long)} so negative animal IDs phase correctly.
     */
    public static boolean shouldRunForAnimal(int tickInterval, long frameCounter, int animalId) {
        if (tickInterval <= 0) {
            return false;
        }
        if (tickInterval == 1) {
            return true;
        }
        return Math.floorMod(frameCounter - (long) animalId, (long) tickInterval) == 0L;
    }

    /**
     * Production wrapper — queries the current interval and the live frame counter from {@link
     * MovingObjectUpdateScheduler}. Called from {@code IsoAnimalUpdateLOSAdvice}.
     */
    public static boolean shouldRunThisTick(int animalId) {
        int stride = currentTickInterval;
        if (stride <= 0) {
            return false;
        }
        if (stride == 1) {
            return true;
        }
        long frame = MovingObjectUpdateScheduler.instance.getFrameCounter();
        return shouldRunForAnimal(stride, frame, animalId);
    }

    private static int clamp(int requested) {
        if (requested < MIN_TICK_INTERVAL) {
            LOGGER.warn(
                    "Storm: animal-LOS tick interval {} below floor, clamping to {}",
                    requested,
                    MIN_TICK_INTERVAL);
            return MIN_TICK_INTERVAL;
        } else if (requested > MAX_TICK_INTERVAL) {
            LOGGER.warn(
                    "Storm: animal-LOS tick interval {} above ceiling, clamping to {}",
                    requested,
                    MAX_TICK_INTERVAL);
            return MAX_TICK_INTERVAL;
        }
        return requested;
    }

    /** Test-only — overrides the current interval without clamping or logging. */
    static void setCurrentTickIntervalForTest(int value) {
        currentTickInterval = value;
    }
}
