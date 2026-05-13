package io.pzstorm.storm.metrics;

import io.prometheus.metrics.model.registry.PrometheusRegistry;

/**
 * Single accessor for Storm's shared Prometheus registry.
 *
 * <p>Storm piggybacks on Project Zomboid's existing Prometheus HTTP server, started by {@code
 * zombie.network.statistics.StatisticManager.init()} when the JVM is launched with {@code
 * -DprometheusPort=<port>}. PZ uses {@link PrometheusRegistry#defaultRegistry}, and Storm + Storm
 * mods register their metrics into the same registry so they are exposed on the same {@code
 * /metrics} endpoint alongside {@code pz_*} and {@code jvm_*}.
 *
 * <p>If {@code -DprometheusPort} is not set, the registry still exists and collectors register
 * normally; nothing is exposed because no HTTP server is running. Recording call sites remain safe
 * either way.
 *
 * <p>Storm and mod authors should register instruments at class-load time and use the standard
 * Prometheus naming conventions (snake_case, base units, {@code _total} suffix for counters):
 *
 * <pre>
 * private static final Counter REQUESTS = Counter.builder()
 *         .name("mymod_requests_total")
 *         .help("Number of requests handled.")
 *         .register(StormPrometheus.registry());
 * </pre>
 */
public final class StormPrometheus {

    private StormPrometheus() {}

    public static PrometheusRegistry registry() {
        return PrometheusRegistry.defaultRegistry;
    }
}
