package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class NetworkPlayerManagerUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_network_player_manager_update_call_duration_seconds")
                    .help("Duration of NetworkPlayerManagerUpdate advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private NetworkPlayerManagerUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
