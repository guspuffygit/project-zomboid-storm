package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.datapoints.DistributionDataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EventDispatchMetrics {

    private static final Counter DISPATCHES =
            Counter.builder()
                    .name("storm_event_dispatch_total")
                    .help("Event dispatches that found matching handlers.")
                    .labelNames("event")
                    .register(StormPrometheus.registry());

    private static final Histogram HANDLER_DURATION =
            Histogram.builder()
                    .name("storm_event_handler_duration_seconds")
                    .help("Duration of dispatching one event to all its handlers.")
                    .labelNames("event")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter ERRORS =
            Counter.builder()
                    .name("storm_event_handler_errors_total")
                    .help("RuntimeExceptions thrown by event handlers.")
                    .labelNames("event")
                    .register(StormPrometheus.registry());

    private static final Map<String, CounterDataPoint> DISPATCH_DP = new ConcurrentHashMap<>();
    private static final Map<String, DistributionDataPoint> DURATION_DP = new ConcurrentHashMap<>();
    private static final Map<String, CounterDataPoint> ERRORS_DP = new ConcurrentHashMap<>();

    static {
        GameTimeMetrics.ensureStarted();
    }

    private EventDispatchMetrics() {}

    public static void recordDispatch(String event) {
        DISPATCH_DP.computeIfAbsent(event, e -> DISPATCHES.labelValues(e)).inc();
    }

    public static void recordHandlerNanos(String event, long nanos) {
        DURATION_DP
                .computeIfAbsent(event, e -> HANDLER_DURATION.labelValues(e))
                .observe(nanos / 1e9);
    }

    public static void recordError(String event) {
        ERRORS_DP.computeIfAbsent(event, e -> ERRORS.labelValues(e)).inc();
    }
}
