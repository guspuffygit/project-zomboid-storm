package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class ObjectIDManagerCheckSaveDataMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_object_id_manager_check_save_data_call_duration_seconds")
                    .help("Duration of ObjectIDManagerCheckSaveData advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private ObjectIDManagerCheckSaveDataMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
