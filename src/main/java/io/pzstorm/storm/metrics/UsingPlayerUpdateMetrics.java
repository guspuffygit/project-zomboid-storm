package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class UsingPlayerUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_using_player_update_call_duration_seconds")
                    .help("Duration of UsingPlayerUpdate advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private UsingPlayerUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
