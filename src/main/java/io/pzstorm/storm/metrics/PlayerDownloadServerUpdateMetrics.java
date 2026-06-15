package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class PlayerDownloadServerUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_player_download_server_update_call_duration_seconds")
                    .help("Duration of PlayerDownloadServer.update advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private PlayerDownloadServerUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
