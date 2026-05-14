package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class RemotePlayerUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_remote_player_update_call_duration_seconds")
                    .help("Duration of RemotePlayerUpdate advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private RemotePlayerUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
