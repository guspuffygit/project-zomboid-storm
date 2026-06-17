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
 *   <li>{@code storm_cell_warm_eligibility_fail_total} — cells that fell through to vanilla unload
 *       because the eligibility predicate rejected them, labelled by {@code reason}.
 *   <li>{@code storm_cell_warm_duration_seconds} — time a cell spent in warm state before either
 *       being rewarmed or fully unloaded.
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

    public static void incEligibilityFail(String reason) {
        ELIGIBILITY_FAILS.labelValues(reason).inc();
    }

    public static void recordWarmDurationNanos(long nanos) {
        WARM_DURATION.observe(nanos / 1e9);
    }
}
