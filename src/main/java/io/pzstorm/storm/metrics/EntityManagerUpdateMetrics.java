package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class EntityManagerUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_entity_manager_update_call_duration_seconds")
                    .help("Duration of EntityManagerUpdate advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private EntityManagerUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
