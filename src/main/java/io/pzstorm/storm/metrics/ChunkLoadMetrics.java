package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class ChunkLoadMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_chunk_load_call_duration_seconds")
                    .help("Duration of ChunkLoad advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private ChunkLoadMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
