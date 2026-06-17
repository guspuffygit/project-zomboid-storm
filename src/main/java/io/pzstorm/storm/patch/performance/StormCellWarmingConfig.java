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
 * the flag is set on the JVM command line and cannot change at runtime.
 */
public final class StormCellWarmingConfig {

    private static final boolean ENABLED = Boolean.getBoolean("storm.cells.keepWarm");

    private StormCellWarmingConfig() {}

    public static boolean isEnabled() {
        return ENABLED;
    }
}
