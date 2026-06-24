package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

/**
 * Metrics for {@code GameServer} net-data advice timing.
 *
 * <p>{@link #CALL_DURATION} is a native histogram — buckets grow dynamically as observations land,
 * so no upper-bound choice is baked in. Requires a Prometheus server with native histograms
 * enabled.
 *
 * <p>{@link #DEFERRED_TOTAL} counts every {@code mainLoopDealWithNetData} invocation
 * short-circuited by {@link io.pzstorm.storm.advice.netdatadraincap.MainLoopDrainCapAdvice} because
 * its per-spin budget was exceeded. A non-zero rate during a reconnect storm confirms the cap is
 * engaging; a sustained non-zero rate under steady-state load indicates the cap (the {@code
 * Storm.NetDataCapMs} sandbox option) is too tight.
 */
public final class NetDataMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_netdata_call_duration_seconds")
                    .help("Duration of GameServer NetData advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter DEFERRED_TOTAL =
            Counter.builder()
                    .name("pz_netdata_deferred_total")
                    .help(
                            "Number of GameServer.mainLoopDealWithNetData calls short-circuited"
                                    + " because the per-spin drain cap (Storm.NetDataCapMs sandbox"
                                    + " option) was exceeded.")
                    .register(StormPrometheus.registry());

    private NetDataMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }

    public static void recordDeferred() {
        DEFERRED_TOTAL.inc();
    }
}
