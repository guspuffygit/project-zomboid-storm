package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

public final class BaseVehicleUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_base_vehicle_update_call_duration_seconds")
                    .help("Duration of BaseVehicleUpdate advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter TICKS =
            Counter.builder()
                    .name("pz_base_vehicle_update_ticks_total")
                    .help("BaseVehicleUpdate ticks observed.")
                    .register(StormPrometheus.registry());

    private BaseVehicleUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }

    public static void recordTick() {
        TICKS.inc();
    }
}
