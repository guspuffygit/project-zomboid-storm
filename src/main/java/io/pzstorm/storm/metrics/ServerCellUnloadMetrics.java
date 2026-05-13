package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

public final class ServerCellUnloadMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_server_cell_unload_call_duration_seconds")
                    .help("Duration of ServerCellUnload advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter TICKS =
            Counter.builder()
                    .name("pz_server_cell_unload_ticks_total")
                    .help("ServerCellUnload ticks observed.")
                    .register(StormPrometheus.registry());

    private ServerCellUnloadMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }

    public static void recordTick() {
        TICKS.inc();
    }
}
