package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

public final class ObjectRemoveFromWorldMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_object_remove_from_world_call_duration_seconds")
                    .help("Duration of ObjectRemoveFromWorld advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter TICKS =
            Counter.builder()
                    .name("pz_object_remove_from_world_ticks_total")
                    .help("ObjectRemoveFromWorld ticks observed.")
                    .register(StormPrometheus.registry());

    private ObjectRemoveFromWorldMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }

    public static void recordTick() {
        TICKS.inc();
    }
}
