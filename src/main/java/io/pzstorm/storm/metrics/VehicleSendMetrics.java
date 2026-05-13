package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

public final class VehicleSendMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_vehicle_send_call_duration_seconds")
                    .help("Duration of VehicleSend advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter TICKS =
            Counter.builder()
                    .name("pz_vehicle_send_ticks_total")
                    .help("VehicleSend ticks observed.")
                    .register(StormPrometheus.registry());

    private VehicleSendMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }

    public static void recordTick() {
        TICKS.inc();
    }
}
