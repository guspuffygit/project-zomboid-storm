package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.metrics.Counter;

public final class ServerLOSIsCouldSeeMetrics {

    private static final Counter CALLS =
            Counter.builder()
                    .name("pz_server_los_could_see_calls_total")
                    .help("ServerLOS.isCouldSee invocations by visibility outcome.")
                    .labelNames("outcome")
                    .register(StormPrometheus.registry());

    private static final CounterDataPoint VISIBLE = CALLS.labelValues("visible");
    private static final CounterDataPoint NOT_VISIBLE = CALLS.labelValues("not_visible");

    private ServerLOSIsCouldSeeMetrics() {}

    public static void recordCall(boolean visible) {
        if (visible) {
            VISIBLE.inc();
        } else {
            NOT_VISIBLE.inc();
        }
    }
}
