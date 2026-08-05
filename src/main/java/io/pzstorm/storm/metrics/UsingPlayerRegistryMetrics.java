package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.pzstorm.storm.entity.UsingPlayerRegistry;

/**
 * Tallies for the {@code UsingPlayerRegistry} sweep on {@code UsingPlayerUpdateSystem.update()},
 * exposed via scrape-time callbacks.
 *
 * <p>The sweep tallies are plain (non-atomic) {@code long}s, same discipline as {@link
 * PlayerLosFastPathMetrics}: all writers run on the server main thread (one increment per tick from
 * {@code runSweep}), and the scrape thread reads dirty, which is acceptable for monotonic counters.
 * The registry-size gauge reads {@link UsingPlayerRegistry#size()} at scrape time (a briefly-held
 * uncontended lock over a ~0–10 element set).
 */
public final class UsingPlayerRegistryMetrics {

    /** Main-thread writers only — see class doc before adding call sites off the main thread. */
    public static long optimizedSweeps;

    public static long vanillaSweeps;

    @SuppressWarnings("unused")
    private static final CounterWithCallback SWEEPS =
            CounterWithCallback.builder()
                    .name("pz_using_player_update_sweeps_total")
                    .help(
                            "UsingPlayerUpdateSystem.update() invocations by executed path:"
                                    + " optimized = the UsingPlayerRegistry sweep ran and the"
                                    + " vanilla full-bucket scan was skipped; vanilla = fell"
                                    + " through to the vanilla body (kill switch off or failure"
                                    + " latch).")
                    .labelNames("path")
                    .callback(
                            callback -> {
                                callback.call((double) optimizedSweeps, "optimized");
                                callback.call((double) vanillaSweeps, "vanilla");
                            })
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final GaugeWithCallback REGISTRY_SIZE =
            GaugeWithCallback.builder()
                    .name("storm_using_player_registry_size")
                    .help(
                            "GameEntities currently registered as having a non-null usingPlayer"
                                    + " (roughly the number of players with a crafting/entity UI"
                                    + " open). The optimized sweep iterates exactly this set"
                                    + " instead of the 100k+ entity iso-object bucket.")
                    .callback(callback -> callback.call(UsingPlayerRegistry.size()))
                    .register(StormPrometheus.registry());

    private UsingPlayerRegistryMetrics() {}

    /** One optimized sweep completed (vanilla body skipped). */
    public static void recordOptimized() {
        optimizedSweeps++;
    }

    /** One call fell through to the vanilla full-bucket scan. */
    public static void recordVanilla() {
        vanillaSweeps++;
    }
}
