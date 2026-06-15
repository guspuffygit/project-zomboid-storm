package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class SafeHouseUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_safe_house_update_call_duration_seconds")
                    .help("Duration of SafeHouseUpdate advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private SafeHouseUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
