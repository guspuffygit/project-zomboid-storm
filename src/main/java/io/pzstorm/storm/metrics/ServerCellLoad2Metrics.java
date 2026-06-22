package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class ServerCellLoad2Metrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_server_cell_load2_call_duration_seconds")
                    .help("Duration of ServerCell.Load2 advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private ServerCellLoad2Metrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
