package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

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

    private EventDispatchMetrics() {}

    public static void recordDispatch(String event) {
        DISPATCHES.labelValues(event).inc();
    }

    public static void recordHandlerNanos(String event, long nanos) {
        HANDLER_DURATION.labelValues(event).observe(nanos / 1e9);
    }

    public static void recordError(String event) {
        ERRORS.labelValues(event).inc();
    }
}
