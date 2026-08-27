package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class BaseVehicleUpdateMetrics {

    /**
     * Only 1 in {@code -Dstorm.vehicle.metricsSampleRate} (default {@value #DEFAULT_SAMPLE_RATE})
     * update calls is timed: the per-call nanoTime pair was 1.9% of total vehicle update cost.
     * Sampling preserves the duration distribution; histogram count/sum are scaled down by the
     * rate.
     */
    public static final int DEFAULT_SAMPLE_RATE = 16;

    private static final int SAMPLE_RATE =
            Math.max(1, Integer.getInteger("storm.vehicle.metricsSampleRate", DEFAULT_SAMPLE_RATE));

    /** Main-thread only. */
    private static long calls;

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_base_vehicle_update_call_duration_seconds")
                    .help(
                            "Duration of BaseVehicleUpdate advice invocations (sampled 1 in"
                                    + " storm.vehicle.metricsSampleRate calls).")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private BaseVehicleUpdateMetrics() {}

    public static boolean shouldSample() {
        return ++calls % SAMPLE_RATE == 0L;
    }

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
