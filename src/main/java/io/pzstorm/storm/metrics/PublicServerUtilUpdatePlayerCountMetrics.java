package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class PublicServerUtilUpdatePlayerCountMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_public_server_util_update_player_count_call_duration_seconds")
                    .help(
                            "Duration of PublicServerUtil.updatePlayerCountIfChanged advice"
                                    + " invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private PublicServerUtilUpdatePlayerCountMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
