package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Tallies for the {@code StormAnimalLos} fast path on {@code IsoAnimal.updateLOS()}. Plain
 * main-thread {@code long}s read dirty at scrape time, same discipline as {@link
 * PlayerLosFastPathMetrics}.
 */
public final class AnimalLosFastPathMetrics {

    public static long optimizedCalls;
    public static long vanillaCalls;
    public static long candidateObjects;
    public static long spottedCalls;
    public static long emulatedCalls;

    @SuppressWarnings("unused")
    private static final CounterWithCallback CALLS =
            CounterWithCallback.builder()
                    .name("pz_animal_update_los_calls_total")
                    .help(
                            "IsoAnimal.updateLOS() invocations that ran (stride permitting) by"
                                    + " executed path: optimized = StormAnimalLos radius query ran"
                                    + " and the vanilla whole-cell walk was skipped; vanilla = fell"
                                    + " through to the vanilla body (spatial index not ready,"
                                    + " failure latch, or animal missing behavior/definition).")
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
                    .name("pz_animal_update_los_objects_total")
                    .help(
                            "Objects handled by the StormAnimalLos fast path: candidate = returned"
                                    + " by the chunk-rectangle query; spotted = real"
                                    + " BaseAnimalBehavior.spotted() calls made; emulated = far"
                                    + " zombies/players whose effect-free spotted() call was"
                                    + " replaced by the lastAlerted-decay/spottedChr emulation.")
                    .labelNames("outcome")
                    .callback(
                            callback -> {
                                callback.call((double) candidateObjects, "candidate");
                                callback.call((double) spottedCalls, "spotted");
                                callback.call((double) emulatedCalls, "emulated");
                            })
                    .register(StormPrometheus.registry());

    private AnimalLosFastPathMetrics() {}

    public static void recordOptimized(int candidates, int spotted, int emulated) {
        optimizedCalls++;
        candidateObjects += candidates;
        spottedCalls += spotted;
        emulatedCalls += emulated;
    }

    public static void recordVanilla() {
        vanillaCalls++;
    }
}
