package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

public final class HttpEndpointMetrics {

    private static final Counter REQUESTS =
            Counter.builder()
                    .name("storm_http_requests_total")
                    .help("HTTP requests handled by Storm's endpoint dispatcher.")
                    .labelNames("method", "path", "status")
                    .register(StormPrometheus.registry());

    private static final Histogram REQUEST_DURATION =
            Histogram.builder()
                    .name("storm_http_request_duration_seconds")
                    .help("HTTP request duration in Storm's endpoint dispatcher.")
                    .labelNames("method", "path")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private HttpEndpointMetrics() {}

    public static void recordRequest(String method, String path, int status) {
        REQUESTS.labelValues(method, path, Integer.toString(status)).inc();
    }

    public static void recordDuration(String method, String path, long nanos) {
        REQUEST_DURATION.labelValues(method, path).observe(nanos / 1e9);
    }
}
