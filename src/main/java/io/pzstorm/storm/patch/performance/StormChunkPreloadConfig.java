package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime knob for the {@code Storm.PreloadChunkOnRecalc} sandbox option (EXPERIMENTAL).
 *
 * <p>When enabled, the patched {@code ServerChunkLoader.RecalcAllThread} runs each chunk's {@code
 * IsoChunk.doLoadGridsquare()} on the worker thread BEFORE republishing the cell to the main
 * thread. The main thread's later {@code ServerCell.RecalcAll2()} skips per-chunk {@code
 * doLoadGridsquare} (gated by {@link StormChunkPreloadState}) so the bulk of the freeze collapses
 * onto the worker.
 *
 * <p>EXPERIMENTAL: the vanilla {@code doLoadGridsquare} call chain touches global singletons
 * ({@code VirtualZombieManager.instance}, {@code VehiclesDB2.instance}, {@code
 * IsoWorld.currentCell}, {@code CorpseCount.instance}) that are not thread-safe with respect to
 * concurrent main-thread access. Default off; opt in only for testing.
 */
public final class StormChunkPreloadConfig {

    public static final boolean DEFAULT_ENABLED = false;

    private static final AtomicBoolean ENABLED = new AtomicBoolean(DEFAULT_ENABLED);

    private StormChunkPreloadConfig() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static boolean setEnabled(boolean enabled) {
        ENABLED.set(enabled);
        StormPerformanceSandboxMetrics.setChunkPreloadOnRecalc(enabled);
        return enabled;
    }
}
