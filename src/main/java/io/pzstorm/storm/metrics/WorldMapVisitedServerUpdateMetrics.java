package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class WorldMapVisitedServerUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_world_map_visited_server_update_call_duration_seconds")
                    .help("Duration of WorldMapVisitedServerUpdate advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private WorldMapVisitedServerUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
