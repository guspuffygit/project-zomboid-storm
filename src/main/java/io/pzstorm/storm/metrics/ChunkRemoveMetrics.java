package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

public final class ChunkRemoveMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_chunk_remove_call_duration_seconds")
                    .help("Duration of IsoChunk.removeFromWorld() advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter TICKS =
            Counter.builder()
                    .name("pz_chunk_remove_ticks_total")
                    .help("ChunkRemove ticks observed.")
                    .register(StormPrometheus.registry());

    private ChunkRemoveMetrics() {}

    public static void recordRemoveNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }

    public static void recordTick() {
        TICKS.inc();
    }
}
