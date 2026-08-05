package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Tallies for the {@code StormFluidContainerUpdate} fast path on {@code
 * FluidContainerUpdateSystem.updateSimulation()}, exposed via scrape-time callbacks.
 *
 * <p>The tallies are plain (non-atomic) {@code long}s, same discipline as {@link
 * PlayerLosFastPathMetrics}: all writers run on the server main thread ({@code runOptimized}
 * accumulates per-pass locals and flushes once per pass), and the scrape thread reads dirty, which
 * is acceptable for monotonic counters.
 */
public final class FluidContainerUpdateMetrics {

    /** Main-thread writers only — see class doc before adding call sites off the main thread. */
    public static long optimizedPasses;

    public static long vanillaPasses;
    public static long shortCircuitedEntities;
    public static long workedEntities;

    @SuppressWarnings("unused")
    private static final CounterWithCallback PASSES =
            CounterWithCallback.builder()
                    .name("pz_fluid_container_update_passes_total")
                    .help(
                            "FluidContainerUpdateSystem.updateSimulation() invocations by executed"
                                    + " path: optimized = the hoisted/reordered"
                                    + " StormFluidContainerUpdate pass ran and the vanilla body was"
                                    + " skipped; vanilla = fell through to the vanilla body (kill"
                                    + " switch off or failure latch).")
                    .labelNames("path")
                    .callback(
                            callback -> {
                                callback.call((double) optimizedPasses, "optimized");
                                callback.call((double) vanillaPasses, "vanilla");
                            })
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback ENTITIES =
            CounterWithCallback.builder()
                    .name("pz_fluid_container_update_entities_total")
                    .help(
                            "FluidContainer entities examined by the optimized pass (validity- and"
                                    + " meta-filtered entities excluded, as in vanilla):"
                                    + " short_circuited = exited on the cheap guards (cannot be"
                                    + " emptied, no rain catcher, or empty with no rain applicable)"
                                    + " before any fluid-list work; worked = ran the petrol"
                                    + " comparison and/or the rain-fill branch.")
                    .labelNames("outcome")
                    .callback(
                            callback -> {
                                callback.call((double) shortCircuitedEntities, "short_circuited");
                                callback.call((double) workedEntities, "worked");
                            })
                    .register(StormPrometheus.registry());

    private FluidContainerUpdateMetrics() {}

    /**
     * One optimized pass completed; {@code shortCircuited}/{@code worked} are its per-pass tallies.
     */
    public static void recordOptimized(long shortCircuited, long worked) {
        optimizedPasses++;
        shortCircuitedEntities += shortCircuited;
        workedEntities += worked;
    }

    /** One call fell through to the vanilla body. */
    public static void recordVanilla() {
        vanillaPasses++;
    }
}
