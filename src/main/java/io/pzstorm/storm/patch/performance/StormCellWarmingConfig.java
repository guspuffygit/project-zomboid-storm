package io.pzstorm.storm.patch.performance;

/**
 * Runtime gate for Storm's cell-warming feature.
 *
 * <p>When enabled, Storm keeps {@code IsoCell} state resident in memory past the point at which
 * vanilla would unload it, eliminating the load/unload thrash that occurs when players walk the
 * same boundary repeatedly or when a chunk re-enters the active frustum shortly after eviction.
 *
 * <p>Opt-in via {@code -Dstorm.cells.keepWarm=true}; off by default so existing servers retain
 * vanilla unload semantics until they explicitly choose otherwise. Read once at class-load time —
 * the flags are set on the JVM command line and cannot change at runtime.
 *
 * <p>{@code -Dstorm.cells.maxWarm=<n>} bounds how many cells may be held warm at once (default
 * {@value #DEFAULT_MAX_WARM_CELLS}). A warm cell keeps its full chunk/square state resident, so
 * without a cap the warm set grows with every cell any player has ever walked away from. When the
 * cap is exceeded the oldest-warmed cells are evicted through the vanilla destructive unload path.
 * Zero or negative disables the bound (previous unbounded behavior).
 */
public final class StormCellWarmingConfig {

    private static final int DEFAULT_MAX_WARM_CELLS = 64;

    private static final boolean ENABLED = Boolean.getBoolean("storm.cells.keepWarm");

    private static final int MAX_WARM_CELLS =
            Integer.getInteger("storm.cells.maxWarm", DEFAULT_MAX_WARM_CELLS);

    private StormCellWarmingConfig() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    /** Maximum number of warm cells held in memory; {@code <= 0} means unbounded. */
    public static int maxWarmCells() {
        return MAX_WARM_CELLS;
    }
}
