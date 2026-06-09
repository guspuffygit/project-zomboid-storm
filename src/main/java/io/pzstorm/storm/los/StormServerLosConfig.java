package io.pzstorm.storm.los;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime configuration for the parallel ServerLOS engine.
 *
 * <p>{@code threads} is the number of players whose LOS scan may run concurrently, one per scratch
 * slot. It is capped at {@link #MAX} (= {@code 16}). Vanilla's per-slot scratch state is only
 * length 4 ({@code LosUtil.cachedresults} / {@code LosUtil.cachecleared} and the per-square {@code
 * IsoGridSquare.lighting}); Storm enlarges all of it to {@link #MAX} so the higher slots stay
 * disjoint: the {@code LosUtil} static arrays are reassigned during {@link StormServerLos} init,
 * and the per-square {@code IsoGridSquare.lighting} array is grown to {@link #MAX} by the
 * server-only {@code IsoGridSquareLosParallelPatch}.
 *
 * <p>The default is {@code 1}: the Storm engine still drives {@code runInner}, but runs the batch
 * single-threaded on slot 0 (the onSee lock and helper pool stay inert and the {@code visible} grid
 * is byte-identical to vanilla). This single-threaded run still records {@code storm_serverlos_*},
 * giving a baseline directly comparable to {@code threads >= 2}.
 *
 * <p>Initialised from {@code -Dstorm.serverLos.threads}; sandbox-loaded from {@code
 * Storm.ServerLosThreads} at {@code OnServerStarted}. Changing the value takes effect on the next
 * LOS tick — no pool rebuild is needed because the worker pool is always sized for {@link #MAX}
 * pre-started helpers (see {@link StormServerLos}) and only dispatches the currently-configured
 * number of slices.
 */
public final class StormServerLosConfig {

    public static final int MIN = 1;
    public static final int MAX = 16;
    public static final int DEFAULT_THREADS = MIN;

    private static final AtomicInteger THREADS =
            new AtomicInteger(
                    clamp(Integer.getInteger("storm.serverLos.threads", DEFAULT_THREADS)));

    private StormServerLosConfig() {}

    public static int threads() {
        return THREADS.get();
    }

    /**
     * Sets the worker count (clamped to {@code [MIN, MAX]}); returns the value actually applied.
     */
    public static int setThreads(int n) {
        int clamped = clamp(n);
        THREADS.set(clamped);
        StormPerformanceSandboxMetrics.setServerLosThreads(clamped);
        return clamped;
    }

    private static int clamp(int n) {
        return Math.max(MIN, Math.min(MAX, n));
    }
}
