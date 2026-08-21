package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.prometheus.metrics.core.metrics.Histogram;

/** Metrics for {@code StormWorldMapVisibilityMemo}. Main-thread writers only (see class docs). */
public final class WorldMapVisibilityMemoMetrics {

    public static long memoEvaluations;
    public static long vanillaEvaluations;
    private static long builds;
    private static long failures;

    private static final Histogram BUILD_DURATION =
            Histogram.builder()
                    .name("storm_worldmap_visibility_memo_build_duration_seconds")
                    .help(
                            "Time to index factions and safehouses by username at the start of each"
                                    + " sendWorldMapPlayerPosition batch.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback EVALUATIONS =
            CounterWithCallback.builder()
                    .name("pz_world_map_visibility_predicate_total")
                    .help(
                            "shouldSendWorldMapPlayerPosition evaluations by path: memo = answered"
                                    + " from the per-batch username index; vanilla = vanilla"
                                    + " linear scans ran (outside a batch, or memo latched off).")
                    .labelNames("path")
                    .callback(
                            callback -> {
                                callback.call((double) memoEvaluations, "memo");
                                callback.call((double) vanillaEvaluations, "vanilla");
                            })
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback BUILDS =
            CounterWithCallback.builder()
                    .name("storm_worldmap_visibility_memo_builds_total")
                    .help("Memo table builds by outcome; a failed outcome latches the memo off.")
                    .labelNames("outcome")
                    .callback(
                            callback -> {
                                callback.call((double) builds, "ok");
                                callback.call((double) failures, "failed");
                            })
                    .register(StormPrometheus.registry());

    private WorldMapVisibilityMemoMetrics() {}

    public static void recordBuild(long nanos) {
        builds++;
        BUILD_DURATION.observe(nanos / 1e9);
    }

    public static void recordFailure() {
        failures++;
    }

    public static long failures() {
        return failures;
    }
}
