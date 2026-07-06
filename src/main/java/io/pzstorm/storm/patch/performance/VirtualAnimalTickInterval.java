package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import zombie.GameTime;
import zombie.MovingObjectUpdateScheduler;
import zombie.network.GameServer;

/**
 * Global tick stride for the static {@code AnimalZones.updateVirtualAnimals()} pass on the
 * dedicated server.
 *
 * <p>Vanilla runs the full virtual-animal simulation (every tracked chunk: track expiry, animal
 * movement, eat/sleep/breed state) once per tick from {@code IsoWorld.update()}. With stride N, the
 * whole pass runs only on ticks where {@code frameCounter mod N == 0}; on those ticks {@code
 * GameTime.perObjectMultiplier} is raised to N for the duration of the call, so time-delta-driven
 * logic ({@code VirtualAnimalState} StateFollow movement, {@code AnimalChunk} track expiry — both
 * read {@code GameTime.getMultiplier()}) advances by the N skipped ticks' worth of time.
 * Absolute-clock logic (eat/sleep schedules keyed to world-age hours) is unaffected by the
 * multiplier and simply evaluates less often. This is vanilla's own compensation mechanism — {@code
 * MovingObjectUpdateScheduler} uses {@code perObjectMultiplier} the same way for bucketed zombies.
 *
 * <p>Default {@link #DEFAULT_TICK_INTERVAL} = 1 preserves vanilla every-tick behavior. 0 disables
 * the pass entirely (virtual animals freeze and tracks stop expiring — debug/emergency use only).
 * The ceiling is deliberately lower than {@code AnimalLOSTickInterval}'s: each executing tick
 * advances the simulation N ticks at once, so large N makes virtual-animal movement visibly jumpy
 * on the world-map track overlay.
 *
 * <p>The live value is mutable via {@link #setTickInterval(int)}; the {@code
 * Storm.VirtualAnimalTickInterval} sandbox option feeds through that setter at {@code
 * OnServerStarted}.
 */
public final class VirtualAnimalTickInterval {

    /** Vanilla — the virtual-animal pass runs every tick. */
    public static final int DEFAULT_TICK_INTERVAL = 1;

    /** 0 disables the pass entirely; 1 = vanilla every-tick. */
    public static final int MIN_TICK_INTERVAL = 0;

    /** Hard ceiling — the compensated catch-up jump grows linearly with the stride. */
    public static final int MAX_TICK_INTERVAL = 16;

    private static volatile int currentTickInterval = DEFAULT_TICK_INTERVAL;

    // updateVirtualAnimals runs only from IsoWorld.update on the main thread, so the
    // save/restore pair below needs no synchronization.
    private static float savedPerObjectMultiplier = 1.0F;
    private static boolean compensating;

    private VirtualAnimalTickInterval() {}

    /** Current effective tick interval. */
    public static int getCurrentTickInterval() {
        return currentTickInterval;
    }

    /**
     * Live-updates the stride, clamping to {@link #MIN_TICK_INTERVAL}..{@link #MAX_TICK_INTERVAL}.
     * Returns the value actually applied.
     */
    public static int setTickInterval(int requested) {
        int applied = clamp(requested);
        currentTickInterval = applied;
        StormPerformanceSandboxMetrics.setVirtualAnimalTickInterval(applied);
        LOGGER.info("Storm: virtual-animal tick interval updated to {}", applied);
        return applied;
    }

    /**
     * Pure, deterministic predicate: whether the pass runs at frame {@code frameCounter} given
     * {@code tickInterval}. Stride 0 never runs; stride 1 always runs.
     */
    public static boolean shouldRunOnFrame(int tickInterval, long frameCounter) {
        if (tickInterval <= 0) {
            return false;
        }
        if (tickInterval == 1) {
            return true;
        }
        return Math.floorMod(frameCounter, (long) tickInterval) == 0L;
    }

    /**
     * Called by {@code AnimalZonesVirtualAnimalStrideAdvice.onEnter}. Returns {@code true} when the
     * vanilla body must be skipped this tick. On executing ticks with stride &gt; 1, raises {@code
     * GameTime.perObjectMultiplier} for the duration of the call; {@link #endCompensatedRun()}
     * (invoked from the advice exit, including on throw) restores it.
     */
    public static boolean shouldSkipThisTick() {
        if (!GameServer.server) {
            return false;
        }
        int stride = currentTickInterval;
        if (stride == 1) {
            return false;
        }
        if (stride <= 0) {
            return true;
        }
        long frame = MovingObjectUpdateScheduler.instance.getFrameCounter();
        if (!shouldRunOnFrame(stride, frame)) {
            return true;
        }
        GameTime gameTime = GameTime.getInstance();
        savedPerObjectMultiplier = gameTime.perObjectMultiplier;
        // Multiply rather than assign so any enclosing per-object scaling still applies.
        gameTime.perObjectMultiplier = savedPerObjectMultiplier * stride;
        compensating = true;
        return false;
    }

    /** Restores {@code perObjectMultiplier} after a compensated run. Idempotent. */
    public static void endCompensatedRun() {
        if (!compensating) {
            return;
        }
        GameTime.getInstance().perObjectMultiplier = savedPerObjectMultiplier;
        compensating = false;
    }

    private static int clamp(int requested) {
        if (requested < MIN_TICK_INTERVAL) {
            LOGGER.warn(
                    "Storm: virtual-animal tick interval {} below floor, clamping to {}",
                    requested,
                    MIN_TICK_INTERVAL);
            return MIN_TICK_INTERVAL;
        } else if (requested > MAX_TICK_INTERVAL) {
            LOGGER.warn(
                    "Storm: virtual-animal tick interval {} above ceiling, clamping to {}",
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
