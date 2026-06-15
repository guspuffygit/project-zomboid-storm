package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class IngameStateUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_ingame_state_update_call_duration_seconds")
                    .help("Duration of IngameStateUpdate advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private IngameStateUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
