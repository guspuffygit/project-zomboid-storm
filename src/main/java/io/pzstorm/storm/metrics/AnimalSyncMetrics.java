package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

public final class AnimalSyncMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_animal_sync_call_duration_seconds")
                    .help("Duration of AnimalSync advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter TICKS =
            Counter.builder()
                    .name("pz_animal_sync_ticks_total")
                    .help("AnimalSync ticks observed.")
                    .register(StormPrometheus.registry());

    private AnimalSyncMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }

    public static void recordTick() {
        TICKS.inc();
    }
}
