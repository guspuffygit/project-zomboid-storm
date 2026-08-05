package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Tallies for the {@code StormPlayerLos} fast path on {@code IsoPlayer.updateLOS()}, exposed via
 * scrape-time callbacks.
 *
 * <p>The tallies are plain (non-atomic) {@code long}s, same discipline as {@link
 * ServerLOSIsCouldSeeMetrics}: this call path runs per player × per moving object per tick on the
 * server main thread, and an eager atomic {@code Counter.inc()} at that frequency once cost ~8% of
 * the main thread. All writers run on the main thread ({@code runOptimized} accumulates per-call
 * locals and flushes once per call); the scrape thread reads dirty, which is acceptable for
 * monotonic counters.
 */
public final class PlayerLosFastPathMetrics {

    /** Main-thread writers only — see class doc before adding call sites off the main thread. */
    public static long optimizedCalls;

    public static long vanillaCalls;
    public static long culledObjects;
    public static long processedObjects;

    @SuppressWarnings("unused")
    private static final CounterWithCallback CALLS =
            CounterWithCallback.builder()
                    .name("pz_player_update_los_calls_total")
                    .help(
                            "IsoPlayer.updateLOS() invocations by executed path: optimized ="
                                    + " StormPlayerLos fast path ran and the vanilla body was"
                                    + " skipped; vanilla = fell through to the vanilla body (kill"
                                    + " switch off, failure latch, or no PlayerData cached yet).")
                    .labelNames("path")
                    .callback(
                            callback -> {
                                callback.call((double) optimizedCalls, "optimized");
                                callback.call((double) vanillaCalls, "vanilla");
                            })
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback OBJECTS =
            CounterWithCallback.builder()
                    .name("pz_player_update_los_objects_total")
                    .help(
                            "Moving objects examined by the StormPlayerLos fast path: culled ="
                                    + " rejected by the visibility-cube distance check before any"
                                    + " cast/visibility work; processed = walked through the"
                                    + " server-stripped loop body.")
                    .labelNames("outcome")
                    .callback(
                            callback -> {
                                callback.call((double) culledObjects, "culled");
                                callback.call((double) processedObjects, "processed");
                            })
                    .register(StormPrometheus.registry());

    private PlayerLosFastPathMetrics() {}

    /** One fast-path call completed; {@code culled}/{@code processed} are its per-call tallies. */
    public static void recordOptimized(long culled, long processed) {
        optimizedCalls++;
        culledObjects += culled;
        processedObjects += processed;
    }

    /** One call fell through to the vanilla body. */
    public static void recordVanilla() {
        vanillaCalls++;
    }
}
