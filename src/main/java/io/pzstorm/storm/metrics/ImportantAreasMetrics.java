package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;

/**
 * Tallies for {@code ImportantAreasPolicy}, the Storm body of {@code
 * ImportantAreaManager.updateOrAdd}, exposed via scrape-time callbacks.
 *
 * <p>Plain (non-atomic) {@code long}s, same discipline as {@link ZombieRainWanderMetrics}: the
 * writer is the server main thread (stove and vehicle updates), the scrape thread reads dirty,
 * which is acceptable for a gauge and monotonic counters.
 *
 * <p>{@code pz_important_areas_size} pinned at {@code storm_important_areas_maximum} with {@code
 * pz_important_area_evictions_total} climbing is the list at its cap: more things want a slot than
 * there are slots, and cooking or an idling engine is being unloaded somewhere every tick. Only
 * written while the policy is active (server JVM, not latched); at rest on a client it stays zero.
 */
public final class ImportantAreasMetrics {

    /** Main-thread writers only. */
    public static long size;

    public static long evictions;

    public static long warnings;

    @SuppressWarnings("unused")
    private static final GaugeWithCallback SIZE =
            GaugeWithCallback.builder()
                    .name("pz_important_areas_size")
                    .help(
                            "Entries in the engine's ImportantAreaManager list after the last"
                                    + " updateOrAdd: 64x64-tile cells kept loaded for a lit stove"
                                    + " or a vehicle with its engine, alarm or siren running while"
                                    + " nobody is near. Pinned at storm_important_areas_maximum"
                                    + " means the cap is binding.")
                    .callback(callback -> callback.call((double) size))
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback EVICTIONS =
            CounterWithCallback.builder()
                    .name("pz_important_area_evictions_total")
                    .help(
                            "updateOrAdd calls that found the list at Storm.ImportantAreasMaximum"
                                    + " and evicted the least-recently-refreshed entry (vanilla"
                                    + " evicts a random one). Each eviction is a cell that will"
                                    + " unload, or go warm, with something still cooking or"
                                    + " running in it.")
                    .callback(callback -> callback.call((double) evictions))
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback WARNINGS =
            CounterWithCallback.builder()
                    .name("pz_important_area_cap_warnings_total")
                    .help(
                            "'ImportantAreas size is too big' lines actually written; Storm"
                                    + " rate-limits them to one per second and folds the eviction"
                                    + " count into the line, where vanilla wrote one per eviction.")
                    .callback(callback -> callback.call((double) warnings))
                    .register(StormPrometheus.registry());

    private ImportantAreasMetrics() {}

    /** Test-only: zeroes the tallies so a case can assert on its own writes. */
    public static void resetForTest() {
        size = 0;
        evictions = 0;
        warnings = 0;
    }
}
