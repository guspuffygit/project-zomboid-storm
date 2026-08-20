package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;

/**
 * Prometheus instruments for Storm's {@code zombie.util.Pool} compaction sweep (see {@code
 * io.pzstorm.storm.patch.performance.StormPoolCompaction}).
 *
 * <ul>
 *   <li>{@code storm_pool_max_probe_estimate} — worst average probe length across every pool's
 *       main-thread in-use set, sampled at the start of each world save. This is the leading
 *       indicator: a healthy set sits at 1-2, and unmitigated it has been observed at ~39,000 on a
 *       busy production server. Alert above ~16.
 *   <li>{@code storm_pool_compactions_total} — compactions performed, by pool.
 *   <li>{@code storm_pool_compact_duration_seconds} — wall-clock time of the compacting rehash.
 * </ul>
 */
public final class StormPoolCompactionMetrics {

    private static final Gauge MAX_PROBE_ESTIMATE =
            Gauge.builder()
                    .name("storm_pool_max_probe_estimate")
                    .help(
                            "Worst average probe length (capacity / (_free + 1)) across all"
                                    + " zombie.util.Pool in-use sets on the main thread. 1-2 is healthy;"
                                    + " large values mean the Trove tombstone cliff is forming.")
                    .register(StormPrometheus.registry());

    private static final Counter COMPACTIONS =
            Counter.builder()
                    .name("storm_pool_compactions_total")
                    .help(
                            "Pool in-use sets compacted at the start of a world save because their"
                                    + " probe length exceeded the threshold.")
                    .labelNames("pool")
                    .register(StormPrometheus.registry());

    private static final Histogram COMPACT_DURATION =
            Histogram.builder()
                    .name("storm_pool_compact_duration_seconds")
                    .help("Wall-clock time spent inside THashSet.compact() per compaction.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private StormPoolCompactionMetrics() {}

    public static void setMaxProbeEstimate(double probe) {
        MAX_PROBE_ESTIMATE.set(probe);
    }

    public static void recordCompaction(String pool, double seconds) {
        COMPACTIONS.labelValues(pool).inc();
        COMPACT_DURATION.observe(seconds);
    }
}
