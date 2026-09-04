package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;

/**
 * Runtime gate for Storm's cell-warming feature.
 *
 * <p>When enabled, Storm keeps {@code IsoCell} state resident in memory past the point at which
 * vanilla would unload it, eliminating the load/unload thrash that occurs when players walk the
 * same boundary repeatedly or when a chunk re-enters the active frustum shortly after eviction.
 *
 * <p>Sourced from the {@code Storm.KeepCellsWarm} and {@code Storm.MaxWarmCells} sandbox options
 * (applied at {@code OnServerStarted} and on every admin sandbox push), off by default so existing
 * servers retain vanilla unload semantics until they explicitly choose otherwise. The legacy {@code
 * -Dstorm.cells.keepWarm} / {@code -Dstorm.cells.maxWarm} flags only seed the values before the
 * sandbox options are read; the sandbox values win once the server is up.
 *
 * <p>Both values are live. Enabling starts warming at the next {@code ServerMap.postupdate}.
 * Disabling does <em>not</em> hand postupdate straight back to vanilla — warm cells sit in {@code
 * loadedCells} with their world-system bindings detached, and neither the vanilla loop nor the
 * unload budget knows how to reconnect them — so {@link StormCellWarmer} keeps owning postupdate in
 * a drain mode (no new warms, evict the remaining warm set at the usual per-tick eviction rate)
 * until the set is empty; see {@link StormCellWarmer#isActive()}.
 *
 * <p>{@code Storm.MaxWarmCells} bounds how many cells may be held warm at once (default {@value
 * #DEFAULT_MAX_WARM_CELLS}). A warm cell keeps its full chunk/square state resident, so without a
 * cap the warm set grows with every cell any player has ever walked away from. When the cap is
 * exceeded the oldest-warmed cells are evicted through the vanilla destructive unload path. Zero
 * disables the bound.
 */
public final class StormCellWarmingConfig {

    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_MAX_WARM_CELLS = 128;
    public static final int MAX_MAX_WARM_CELLS = 1024;

    private static volatile boolean enabled = Boolean.getBoolean("storm.cells.keepWarm");

    private static volatile int maxWarmCells =
            clampMax(Integer.getInteger("storm.cells.maxWarm", DEFAULT_MAX_WARM_CELLS));

    private StormCellWarmingConfig() {}

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Single mutation point for the enable flag; sandbox apply and tests both funnel through it.
     * Returns the value now in effect.
     */
    public static boolean setEnabled(boolean on) {
        boolean was = enabled;
        enabled = on;
        StormPerformanceSandboxMetrics.setCellWarmingEnabled(on);
        if (was != on) {
            if (on) {
                StormLogger.LOGGER.info("Cell warming enabled (max warm cells {})", maxWarmCells);
            } else {
                StormLogger.LOGGER.info(
                        "Cell warming disabled — draining {} warm cell(s) through the eviction"
                                + " path before postupdate returns to vanilla",
                        StormCellWarmer.warmCount());
            }
        }
        return on;
    }

    /** Maximum number of warm cells held in memory; {@code 0} means unbounded. */
    public static int maxWarmCells() {
        return maxWarmCells;
    }

    /** Clamps to {@code 0..}{@value #MAX_MAX_WARM_CELLS}, stores, pushes the gauge. */
    public static int setMaxWarmCells(int n) {
        int clamped = clampMax(n);
        maxWarmCells = clamped;
        StormPerformanceSandboxMetrics.setMaxWarmCells(clamped);
        return clamped;
    }

    private static int clampMax(int n) {
        return Math.max(0, Math.min(MAX_MAX_WARM_CELLS, n));
    }
}
