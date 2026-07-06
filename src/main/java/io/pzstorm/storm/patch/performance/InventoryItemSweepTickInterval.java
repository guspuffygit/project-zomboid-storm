package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import zombie.MovingObjectUpdateScheduler;
import zombie.network.GameServer;

/**
 * Global tick stride for {@code InventoryItemSystem.update()} on the dedicated server.
 *
 * <p>Vanilla sweeps every registered inventory-item entity every tick, unregistering items whose
 * equip parent is gone or dead (see {@code zombie/entity/InventoryItemSystem.java:18-29}). The
 * sweep is pure garbage collection and idempotent — an orphaned entity found on tick T is exactly
 * as unregisterable on tick T+N — so striding it only delays entity cleanup by up to N-1 ticks with
 * no gameplay-visible effect.
 *
 * <p>Disable (stride 0) is deliberately not offered: never sweeping would leak orphaned entities in
 * the engine bucket for the life of the server.
 *
 * <p>The live value is mutable via {@link #setTickInterval(int)}; the {@code
 * Storm.InventoryItemSweepTickInterval} sandbox option feeds through that setter at {@code
 * OnServerStarted}.
 */
public final class InventoryItemSweepTickInterval {

    /** Vanilla — the sweep runs every tick. */
    public static final int DEFAULT_TICK_INTERVAL = 1;

    /** The sweep can be strided but never disabled. */
    public static final int MIN_TICK_INTERVAL = 1;

    /** Hard ceiling — 6.4s between sweeps at vanilla 10 TPS; pure GC, so delay is harmless. */
    public static final int MAX_TICK_INTERVAL = 64;

    /**
     * Offsets the executing frame so the sweep does not stack onto the same ticks as the
     * virtual-animal stride when admins configure both to the same interval.
     */
    public static final int PHASE_OFFSET = 1;

    private static volatile int currentTickInterval = DEFAULT_TICK_INTERVAL;

    private InventoryItemSweepTickInterval() {}

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
        StormPerformanceSandboxMetrics.setInventoryItemSweepTickInterval(applied);
        LOGGER.info("Storm: inventory-item sweep tick interval updated to {}", applied);
        return applied;
    }

    /**
     * Pure, deterministic predicate: whether the sweep runs at frame {@code frameCounter} given
     * {@code tickInterval}. Stride ≤ 1 always runs.
     */
    public static boolean shouldRunOnFrame(int tickInterval, long frameCounter) {
        if (tickInterval <= 1) {
            return true;
        }
        return Math.floorMod(frameCounter - PHASE_OFFSET, (long) tickInterval) == 0L;
    }

    /**
     * Called by {@code InventoryItemSweepStrideAdvice.onEnter}. Returns {@code true} when the
     * vanilla sweep body must be skipped this tick.
     */
    public static boolean shouldSkipThisTick() {
        if (!GameServer.server) {
            return false;
        }
        int stride = currentTickInterval;
        if (stride <= 1) {
            return false;
        }
        long frame = MovingObjectUpdateScheduler.instance.getFrameCounter();
        return !shouldRunOnFrame(stride, frame);
    }

    private static int clamp(int requested) {
        if (requested < MIN_TICK_INTERVAL) {
            LOGGER.warn(
                    "Storm: inventory-item sweep tick interval {} below floor, clamping to {}",
                    requested,
                    MIN_TICK_INTERVAL);
            return MIN_TICK_INTERVAL;
        } else if (requested > MAX_TICK_INTERVAL) {
            LOGGER.warn(
                    "Storm: inventory-item sweep tick interval {} above ceiling, clamping to {}",
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
