package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class ZombieSpotPlayerMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_zombie_spot_player_call_duration_seconds")
                    .help("Duration of ZombieSpotPlayer advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private ZombieSpotPlayerMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
