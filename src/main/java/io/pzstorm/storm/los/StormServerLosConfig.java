package io.pzstorm.storm.los;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime configuration for the parallel ServerLOS engine.
 *
 * <p>{@code threads} is the number of players whose LOS scan may run concurrently, one per scratch
 * slot. It is hard-capped at {@link #MAX} (= {@code 4}) because {@code LosUtil.cachedresults} and
 * {@code IsoGridSquare.lighting} are both length-4 per-slot arrays. The default is {@code 1}, which
 * keeps the vanilla single-threaded LOS path (the {@code runInner} advice does not skip the
 * original and the structural patches stay inert).
 *
 * <p>Initialised from {@code -Dstorm.serverLos.threads}; adjustable at runtime via the Storm HTTP
 * endpoint. Changing the value takes effect on the next LOS tick — no pool rebuild is needed
 * because the worker pool is always sized for {@link #MAX} and only dispatches the
 * currently-configured number of slices.
 */
public final class StormServerLosConfig {

    public static final int MIN = 1;
    public static final int MAX = 4;

    private static final AtomicInteger THREADS =
            new AtomicInteger(clamp(Integer.getInteger("storm.serverLos.threads", MIN)));

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
        return clamped;
    }

    private static int clamp(int n) {
        return Math.max(MIN, Math.min(MAX, n));
    }
}
