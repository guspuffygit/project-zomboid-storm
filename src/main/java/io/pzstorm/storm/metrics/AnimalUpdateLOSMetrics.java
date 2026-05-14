package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class AnimalUpdateLOSMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_animal_update_los_call_duration_seconds")
                    .help("Duration of AnimalUpdateLOS advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private AnimalUpdateLOSMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
