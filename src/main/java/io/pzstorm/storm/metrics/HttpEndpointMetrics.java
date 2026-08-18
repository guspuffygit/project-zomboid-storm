package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

/**
 * Request count/duration instruments for one of Storm's HTTP servers. Each server reports under its
 * own metric names so backend and game-port traffic stay distinguishable.
 */
public final class HttpEndpointMetrics {

    /** Storm's backend HTTP server ({@code -Dstorm.http.port}). */
    public static final HttpEndpointMetrics STORM =
            new HttpEndpointMetrics("storm_http", "Storm's backend HTTP endpoint dispatcher");

    /** The game-port HTTP server (TCP on the game's default UDP port). */
    public static final HttpEndpointMetrics GAME_PORT =
            new HttpEndpointMetrics(
                    "storm_gameport_http", "Storm's game-port HTTP endpoint dispatcher");

    private final Counter requests;
    private final Histogram requestDuration;

    private HttpEndpointMetrics(String prefix, String description) {
        this.requests =
                Counter.builder()
                        .name(prefix + "_requests_total")
                        .help("HTTP requests handled by " + description + ".")
                        .labelNames("method", "path", "status")
                        .register(StormPrometheus.registry());
        this.requestDuration =
                Histogram.builder()
                        .name(prefix + "_request_duration_seconds")
                        .help("HTTP request duration in " + description + ".")
                        .labelNames("method", "path")
                        .nativeOnly()
                        .register(StormPrometheus.registry());
    }

    public void recordRequest(String method, String path, int status) {
        requests.labelValues(method, path, Integer.toString(status)).inc();
    }

    public void recordDuration(String method, String path, long nanos) {
        requestDuration.labelValues(method, path).observe(nanos / 1e9);
    }
}
