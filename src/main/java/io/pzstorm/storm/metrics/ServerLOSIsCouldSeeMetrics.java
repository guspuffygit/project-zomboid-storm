package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

public final class ServerLOSIsCouldSeeMetrics {

    private static final Counter CALLS =
            Counter.builder()
                    .name("pz_server_los_could_see_calls_total")
                    .help("ServerLOS.isCouldSee invocations by visibility outcome.")
                    .labelNames("outcome")
                    .register(StormPrometheus.registry());

    private static final CounterDataPoint VISIBLE = CALLS.labelValues("visible");
    private static final CounterDataPoint NOT_VISIBLE = CALLS.labelValues("not_visible");

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_server_los_could_see_call_duration_seconds")
                    .help("Duration of ServerLOS.isCouldSee advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private ServerLOSIsCouldSeeMetrics() {}

    public static void recordCall(long nanos, boolean visible) {
        if (visible) {
            VISIBLE.inc();
        } else {
            NOT_VISIBLE.inc();
        }
        CALL_DURATION.observe(nanos / 1e9);
    }
}
