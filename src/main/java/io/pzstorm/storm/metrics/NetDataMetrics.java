package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

/**
 * Metrics for {@code GameServer} net-data advice timing and the {@code MovingObjectUpdateScheduler}
 * tick rate.
 *
 * <p>Buckets target the microsecond-to-millisecond range observed live (~10µs typical per call).
 */
public final class NetDataMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("storm_netdata_call_duration_seconds")
                    .help("Duration of GameServer NetData advice invocations.")
                    .classicUpperBounds(
                            1e-6, 5e-6, 1e-5, 5e-5, 1e-4, 5e-4, 1e-3, 5e-3, 1e-2, 1e-1, 1.0)
                    .register(StormPrometheus.registry());

    private static final Counter TICKS =
            Counter.builder()
                    .name("storm_netdata_ticks_total")
                    .help("MovingObjectUpdateScheduler ticks observed.")
                    .register(StormPrometheus.registry());

    private NetDataMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1_000_000_000.0);
    }

    public static void recordTick() {
        TICKS.inc();
    }
}
