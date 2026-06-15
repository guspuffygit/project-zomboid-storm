package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class SendWorldMapPlayerPositionMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_send_world_map_player_position_call_duration_seconds")
                    .help("Duration of SendWorldMapPlayerPosition advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private SendWorldMapPlayerPositionMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
