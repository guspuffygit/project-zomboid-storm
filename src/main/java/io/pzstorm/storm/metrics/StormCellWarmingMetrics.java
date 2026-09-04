package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;

/**
 * Prometheus instruments for Storm's cell warming feature (see {@code
 * io.pzstorm.storm.patch.performance.StormCellWarmingConfig}).
 *
 * <ul>
 *   <li>{@code storm_cell_warmed_total} — cells transitioned to warm (Unload short-circuited).
 *   <li>{@code storm_cell_rewarmed_total} — cells re-attached from the warm map (avoided disk read
 *       + binary parse + RecalcAll2).
 *   <li>{@code storm_cell_warm_count} — current number of warm cells held in memory.
 *   <li>{@code storm_cell_warm_evicted_total} — warm cells destructively unloaded because the warm
 *       set exceeded {@code Storm.MaxWarmCells} (or is draining after a live disable).
 *   <li>{@code storm_cell_warm_eligibility_fail_total} — cells that fell through to vanilla unload
 *       because the eligibility predicate rejected them, labelled by {@code reason}.
 *   <li>{@code storm_cell_warm_duration_seconds} — time a cell spent in warm state before either
 *       being rewarmed or fully unloaded.
 *   <li>{@code storm_cell_warm_op_duration_seconds} — wall-clock time spent inside the warm
 *       operation itself (chunk disconnect + animal/dead-body drain).
 *   <li>{@code storm_cell_rewarm_op_duration_seconds} — wall-clock time spent inside the rewarm
 *       operation itself (chunk reconnect + animal/dead-body restore + event dispatch).
 * </ul>
 */
public final class StormCellWarmingMetrics {

    private static final Counter CELLS_WARMED =
            Counter.builder()
                    .name("storm_cell_warmed_total")
                    .help("Cells whose ServerCell.Unload was short-circuited into the warm map.")
                    .register(StormPrometheus.registry());

    private static final Counter CELLS_REWARMED =
            Counter.builder()
                    .name("storm_cell_rewarmed_total")
                    .help(
                            "Cells re-attached from the warm map by ServerMap.loadOrKeepRelevent,"
                                    + " avoiding disk read and RecalcAll2.")
                    .register(StormPrometheus.registry());

    private static final Gauge CELLS_WARM_COUNT =
            Gauge.builder()
                    .name("storm_cell_warm_count")
                    .help("Current number of cells held warm in memory.")
                    .register(StormPrometheus.registry());

    private static final Counter CELLS_EVICTED =
            Counter.builder()
                    .name("storm_cell_warm_evicted_total")
                    .help(
                            "Warm cells destructively unloaded because the warm set exceeded"
                                    + " Storm.MaxWarmCells, or because Storm.KeepCellsWarm was"
                                    + " switched off live and the set is draining.")
                    .register(StormPrometheus.registry());

    private static final Counter ELIGIBILITY_FAILS =
            Counter.builder()
                    .name("storm_cell_warm_eligibility_fail_total")
                    .help(
                            "ServerCell.Unload calls where the eligibility predicate rejected"
                                    + " warming and vanilla destructive unload ran instead.")
                    .labelNames("reason")
                    .register(StormPrometheus.registry());

    private static final Histogram WARM_DURATION =
            Histogram.builder()
                    .name("storm_cell_warm_duration_seconds")
                    .help(
                            "Time a cell spent in warm state before being rewarmed or fully"
                                    + " unloaded.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram WARM_OP_DURATION =
            Histogram.builder()
                    .name("storm_cell_warm_op_duration_seconds")
                    .help(
                            "Wall-clock time spent inside StormCellWarmer.warm() per cell (chunk"
                                    + " disconnect + animal/dead-body drain).")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter EVICT_NEAR_SKIPS =
            Counter.builder()
                    .name("storm_cell_warm_evict_near_skip_total")
                    .help(
                            "LRU-head eviction candidates spared because player influence was"
                                    + " within the near margin — a farther warm cell was evicted"
                                    + " instead of one likely to be rewarmed moments later.")
                    .register(StormPrometheus.registry());

    private static final Gauge WARM_OVER_CAP =
            Gauge.builder()
                    .name("storm_cell_warm_over_cap")
                    .help(
                            "Warm cells above Storm.MaxWarmCells after this tick's evictions;"
                                    + " the per-tick eviction cap trims the excess over the"
                                    + " following ticks. Equals storm_cell_warm_count while"
                                    + " draining after a live disable.")
                    .register(StormPrometheus.registry());

    private static final Histogram REWARM_OP_DURATION =
            Histogram.builder()
                    .name("storm_cell_rewarm_op_duration_seconds")
                    .help(
                            "Wall-clock time spent inside StormCellWarmer.rewarm() per cell (chunk"
                                    + " reconnect + animal/dead-body restore + event dispatch).")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private StormCellWarmingMetrics() {}

    public static void incCellsWarmed() {
        CELLS_WARMED.inc();
    }

    public static void incCellsRewarmed() {
        CELLS_REWARMED.inc();
    }

    public static void setWarmCount(int count) {
        CELLS_WARM_COUNT.set(count);
    }

    public static void incCellsEvicted() {
        CELLS_EVICTED.inc();
    }

    public static void incEligibilityFail(String reason) {
        ELIGIBILITY_FAILS.labelValues(reason).inc();
    }

    public static void recordWarmDurationNanos(long nanos) {
        WARM_DURATION.observe(nanos / 1e9);
    }

    public static void recordWarmOpNanos(long nanos) {
        WARM_OP_DURATION.observe(nanos / 1e9);
    }

    public static void recordRewarmOpNanos(long nanos) {
        REWARM_OP_DURATION.observe(nanos / 1e9);
    }

    public static void incEvictNearSkips(long count) {
        EVICT_NEAR_SKIPS.inc(count);
    }

    public static void setWarmOverCap(int count) {
        WARM_OVER_CAP.set(count);
    }
}
