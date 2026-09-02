package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.pzstorm.storm.inventory.StormClothingVisuals;

/** Metrics for {@code StormClothingVisuals}. Registered on first class use. */
public final class ClothingVisualsMemoMetrics {

    @SuppressWarnings("unused")
    private static final CounterWithCallback HITS =
            CounterWithCallback.builder()
                    .name("storm_clothing_visuals_memo_hits_total")
                    .help(
                            "Thermoregulator/ClothingWetness worn-visual list fills served entirely"
                                    + " from the per-character memo (no InventoryItem.getVisual"
                                    + " call).")
                    .callback(callback -> callback.call((double) StormClothingVisuals.hits))
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback MISSES =
            CounterWithCallback.builder()
                    .name("storm_clothing_visuals_memo_misses_total")
                    .help(
                            "Worn-visual list fills that resolved at least one item through"
                                    + " InventoryItem.getVisual (item or visual changed, or the"
                                    + " item has no visual).")
                    .callback(callback -> callback.call((double) StormClothingVisuals.misses))
                    .register(StormPrometheus.registry());

    private ClothingVisualsMemoMetrics() {}

    /** Forces registration; call once from the patch path so the counters exist before use. */
    public static void init() {}
}
