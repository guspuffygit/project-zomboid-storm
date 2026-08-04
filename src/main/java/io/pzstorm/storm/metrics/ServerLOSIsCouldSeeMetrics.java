package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Call tallies for {@code ServerLOS.isCouldSee}, exposed under the original {@code
 * pz_server_los_could_see_calls_total} name via a scrape-time callback.
 *
 * <p>The tallies are plain (non-atomic) {@code long}s: {@code recordCall} fires per player × per
 * moving object per tick on the server main thread, and the previous eager {@code Counter.inc()}
 * (an atomic add) was the leaf frame in ~8% of main-thread profile samples at that frequency. All
 * writers run on the main thread; the scrape thread reads dirty, which is acceptable for a
 * monotonic counter (a scrape may lag the true count by a few calls; 64-bit HotSpot reads/writes
 * longs atomically in practice).
 */
public final class ServerLOSIsCouldSeeMetrics {

    /** Main-thread writers only — see class doc before adding call sites off the main thread. */
    public static long visibleCalls;

    public static long notVisibleCalls;

    @SuppressWarnings("unused")
    private static final CounterWithCallback CALLS =
            CounterWithCallback.builder()
                    .name("pz_server_los_could_see_calls_total")
                    .help("ServerLOS.isCouldSee invocations by visibility outcome.")
                    .labelNames("outcome")
                    .callback(
                            callback -> {
                                callback.call((double) visibleCalls, "visible");
                                callback.call((double) notVisibleCalls, "not_visible");
                            })
                    .register(StormPrometheus.registry());

    private ServerLOSIsCouldSeeMetrics() {}

    public static void recordCall(boolean visible) {
        if (visible) {
            visibleCalls++;
        } else {
            notVisibleCalls++;
        }
    }
}
