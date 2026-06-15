package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class ServerMapPreUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_server_map_pre_update_call_duration_seconds")
                    .help("Duration of ServerMapPreUpdate advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private ServerMapPreUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
