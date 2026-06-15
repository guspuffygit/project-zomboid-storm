package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class ImportantAreaManagerProcessMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_important_area_manager_process_call_duration_seconds")
                    .help("Duration of ImportantAreaManagerProcess advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private ImportantAreaManagerProcessMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
