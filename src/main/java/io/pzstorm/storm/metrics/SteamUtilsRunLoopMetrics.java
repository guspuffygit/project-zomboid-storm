package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class SteamUtilsRunLoopMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_steam_utils_run_loop_call_duration_seconds")
                    .help("Duration of SteamUtilsRunLoop advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private SteamUtilsRunLoopMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
