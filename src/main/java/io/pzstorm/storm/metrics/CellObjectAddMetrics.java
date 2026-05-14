package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class CellObjectAddMetrics {

    public static final int VANILLA_SAMPLE_MASK = 1023;

    private static final Histogram CALL_DURATION_FAST =
            Histogram.builder()
                    .name("pz_cell_object_add_fast_duration_seconds")
                    .help("Fast-path duration for IsoCell add operations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram CALL_DURATION_VANILLA_SIMULATED =
            Histogram.builder()
                    .name("pz_cell_object_add_vanilla_simulated_duration_seconds")
                    .help(
                            "Simulated vanilla-path duration for IsoCell add operations. Sampled 1-in-1024.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private CellObjectAddMetrics() {}

    public static void recordFastNanos(long nanos) {
        CALL_DURATION_FAST.observe(nanos / 1e9);
    }

    public static void recordVanillaSimulatedNanos(long nanos) {
        CALL_DURATION_VANILLA_SIMULATED.observe(nanos / 1e9);
    }
}
