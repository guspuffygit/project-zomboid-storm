package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class LoginQueueUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_login_queue_update_call_duration_seconds")
                    .help("Duration of LoginQueueUpdate advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private LoginQueueUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
