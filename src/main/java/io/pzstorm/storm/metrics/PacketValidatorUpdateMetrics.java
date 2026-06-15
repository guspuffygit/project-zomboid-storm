package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class PacketValidatorUpdateMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_packet_validator_update_call_duration_seconds")
                    .help("Duration of PacketValidator.update advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private PacketValidatorUpdateMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
