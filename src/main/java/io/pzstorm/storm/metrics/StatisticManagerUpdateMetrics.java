package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class StatisticManagerUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_statistic_manager_update_call_duration_seconds")
                    .help("Duration of StatisticManager.update advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private StatisticManagerUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
