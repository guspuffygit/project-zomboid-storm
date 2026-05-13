package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

public final class PlayerUpdateLOSMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_player_update_los_call_duration_seconds")
                    .help("Duration of PlayerUpdateLOS advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter TICKS =
            Counter.builder()
                    .name("pz_player_update_los_ticks_total")
                    .help("PlayerUpdateLOS ticks observed.")
                    .register(StormPrometheus.registry());

    private PlayerUpdateLOSMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }

    public static void recordTick() {
        TICKS.inc();
    }
}
