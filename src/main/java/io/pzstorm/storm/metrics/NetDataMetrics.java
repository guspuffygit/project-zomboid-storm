package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

/**
 * Metrics for {@code GameServer} net-data advice timing and the {@code MovingObjectUpdateScheduler}
 * tick rate.
 *
 * <p>{@link #CALL_DURATION} is a native histogram — buckets grow dynamically as observations land,
 * so no upper-bound choice is baked in. Requires a Prometheus server with native histograms
 * enabled.
 */
public final class NetDataMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_netdata_call_duration_seconds")
                    .help("Duration of GameServer NetData advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter TICKS =
            Counter.builder()
                    .name("pz_netdata_ticks_total")
                    .help("MovingObjectUpdateScheduler ticks observed.")
                    .register(StormPrometheus.registry());

    private NetDataMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }

    public static void recordTick() {
        TICKS.inc();
    }
}
