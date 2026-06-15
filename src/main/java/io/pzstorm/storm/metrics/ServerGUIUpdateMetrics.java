package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class ServerGUIUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_server_gui_update_call_duration_seconds")
                    .help("Duration of ServerGUIUpdate advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private ServerGUIUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
