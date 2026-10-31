package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import zombie.MovingObjectUpdateScheduler;
import zombie.characters.IsoZombie;
import zombie.network.GameServer;

/**
 * Per-zombie tick stride for {@code NetworkZombieManager.updateAuth(IsoZombie)} on the dedicated
 * server.
 *
 * <p>Vanilla gates the ownership rescan on {@code now - lastChangeOwner >= 2000ms}, but {@code
 * lastChangeOwner} is only stamped on an actual owner <em>change</em>, so the gate holds for 2
 * seconds after a handoff and is then open for good. An unowned zombie bypasses it altogether.
 * Either way a zombie re-runs the relevance scan, O(connections * players), every single frame.
 * With stride N, a zombie scans only on ticks where {@code (frameCounter - onlineID) mod N == 0},
 * spreading the population evenly across the cycle.
 *
 * <p>Owned and unowned zombies take the same stride, so the skip needs no ownership probe: on an
 * off tick it costs one field read per zombie. The only behavioral delta is that any change of
 * owner, a freshly relevant zombie's first owner included, can land up to N-1 ticks later. The
 * ceiling of {@value #MAX_TICK_INTERVAL} bounds that at 1.5&nbsp;s on a vanilla 10-TPS server,
 * inside the 2 seconds vanilla already holds an owner after a change. 0/disable is deliberately not
 * offered: never scanning would leave newly loaded zombies permanently unsimulated.
 *
 * <p>The live value is mutable via {@link #setTickInterval(int)}; the {@code
 * Storm.ZombieAuthTickInterval} sandbox option feeds through that setter at {@code
 * OnServerStarted}.
 */
public final class ZombieAuthTickInterval {

    /** Vanilla: a zombie past the 2 second gate rescans ownership every tick. */
    public static final int DEFAULT_TICK_INTERVAL = 1;

    /** Rescans can be strided but never disabled. */
    public static final int MIN_TICK_INTERVAL = 1;

    /** Hard ceiling — bounds worst-case owner-assignment delay for a newly relevant zombie. */
    public static final int MAX_TICK_INTERVAL = 16;

    private static volatile int currentTickInterval = DEFAULT_TICK_INTERVAL;

    private ZombieAuthTickInterval() {}

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
        StormPerformanceSandboxMetrics.setZombieAuthTickInterval(applied);
        LOGGER.info("Storm: zombie-auth tick interval updated to {}", applied);
        return applied;
    }

    /**
     * Pure, deterministic predicate: whether the zombie with id {@code zombieOnlineId} rescans at
     * frame {@code frameCounter}. Computed with {@link Math#floorMod(long, long)} so negative
     * online IDs phase correctly.
     */
    public static boolean shouldRunForZombie(
            int tickInterval, long frameCounter, int zombieOnlineId) {
        if (tickInterval <= 1) {
            return true;
        }
        return Math.floorMod(frameCounter - (long) zombieOnlineId, (long) tickInterval) == 0L;
    }

    /**
     * Called by {@code NetworkZombieAuthStrideAdvice.onEnter}. Returns {@code true} when the
     * vanilla {@code updateAuth} body must be skipped this tick.
     */
    public static boolean shouldSkipThisTick(IsoZombie zombie) {
        if (!GameServer.server) {
            return false;
        }
        int stride = currentTickInterval;
        if (stride <= 1) {
            return false;
        }
        long frame = MovingObjectUpdateScheduler.instance.getFrameCounter();
        return !shouldRunForZombie(stride, frame, zombie.getOnlineID());
    }

    private static int clamp(int requested) {
        if (requested < MIN_TICK_INTERVAL) {
            LOGGER.warn(
                    "Storm: zombie-auth tick interval {} below floor, clamping to {}",
                    requested,
                    MIN_TICK_INTERVAL);
            return MIN_TICK_INTERVAL;
        } else if (requested > MAX_TICK_INTERVAL) {
            LOGGER.warn(
                    "Storm: zombie-auth tick interval {} above ceiling, clamping to {}",
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
