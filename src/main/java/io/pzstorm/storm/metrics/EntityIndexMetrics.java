package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.pzstorm.storm.entity.StormEntityIndex;

/**
 * Tallies for the {@code StormEntityIndex} O(1) entity-removal fast path, exposed via scrape-time
 * callbacks.
 *
 * <p>The tallies are plain (non-atomic) {@code long}s, same discipline as {@link
 * PlayerLosFastPathMetrics}: all writers run on the server main thread (the only place the engine
 * mutates its entity array); the scrape thread reads dirty, which is acceptable for monotonic
 * counters.
 */
public final class EntityIndexMetrics {

    /** Main-thread writers only — see class doc before adding call sites off the main thread. */
    public static long fastRemovals;

    public static long scanRemovals;
    public static long mismatchRemovals;
    public static long vanillaRemovals;

    @SuppressWarnings("unused")
    private static final CounterWithCallback REMOVES =
            CounterWithCallback.builder()
                    .name("pz_entity_array_removes_total")
                    .help(
                            "Removals from the engine's global entity array by executed path: fast"
                                    + " = O(1) indexed swap-with-last after the identity"
                                    + " self-check passed; scan = handled by Storm's inline linear"
                                    + " scan (index miss around a kill-switch toggle, or an"
                                    + " equals-based call); mismatch = self-check failed, index"
                                    + " desynced, fast path latched off permanently; vanilla ="
                                    + " fell through to the vanilla linear scan (kill switch off"
                                    + " or failure latch).")
                    .labelNames("path")
                    .callback(
                            callback -> {
                                callback.call((double) fastRemovals, "fast");
                                callback.call((double) scanRemovals, "scan");
                                callback.call((double) mismatchRemovals, "mismatch");
                                callback.call((double) vanillaRemovals, "vanilla");
                            })
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final GaugeWithCallback INDEX_SIZE =
            GaugeWithCallback.builder()
                    .name("storm_entity_index_size")
                    .help(
                            "Entities currently tracked by the StormEntityIndex removal index —"
                                    + " mirrors the engine's global entity array size while the"
                                    + " fast path is active.")
                    .callback(callback -> callback.call(StormEntityIndex.indexSize()))
                    .register(StormPrometheus.registry());

    private EntityIndexMetrics() {}
}
