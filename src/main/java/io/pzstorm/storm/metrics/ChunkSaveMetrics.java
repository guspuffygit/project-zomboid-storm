package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class ChunkSaveMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_chunk_save_call_duration_seconds")
                    .help("Duration of ChunkSave advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private ChunkSaveMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
