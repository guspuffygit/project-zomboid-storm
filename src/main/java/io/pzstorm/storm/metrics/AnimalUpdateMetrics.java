package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class AnimalUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_animal_update_call_duration_seconds")
                    .help("Duration of IsoAnimal.update() advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private AnimalUpdateMetrics() {}

    public static void recordUpdateNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
