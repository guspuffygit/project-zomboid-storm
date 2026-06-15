package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class RCONServerUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_rcon_server_update_call_duration_seconds")
                    .help("Duration of RCONServerUpdate advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private RCONServerUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
