package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class MapCollisionDataUpdateGameStateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_map_collision_data_update_game_state_call_duration_seconds")
                    .help("Duration of MapCollisionDataUpdateGameState advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private MapCollisionDataUpdateGameStateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
