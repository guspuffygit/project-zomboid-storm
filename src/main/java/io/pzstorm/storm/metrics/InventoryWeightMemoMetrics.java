package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.pzstorm.storm.inventory.StormInventoryWeight;

/** Metrics for {@code StormInventoryWeight}. Registered on first class use. */
public final class InventoryWeightMemoMetrics {

    @SuppressWarnings("unused")
    private static final CounterWithCallback HITS =
            CounterWithCallback.builder()
                    .name("storm_inv_weight_memo_hits_total")
                    .help(
                            "IsoGameCharacter.getInventoryWeight calls served from the"
                                    + " per-character memo (recursive inventory walk skipped).")
                    .callback(callback -> callback.call((double) StormInventoryWeight.hits))
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback MISSES =
            CounterWithCallback.builder()
                    .name("storm_inv_weight_memo_misses_total")
                    .help(
                            "IsoGameCharacter.getInventoryWeight calls that ran the vanilla walk"
                                    + " (the character's epoch advanced: an inventory, equip,"
                                    + " worn, fluid or item-weight mutation reached it).")
                    .callback(callback -> callback.call((double) StormInventoryWeight.misses))
                    .register(StormPrometheus.registry());

    private InventoryWeightMemoMetrics() {}

    /** Forces registration; call once from the patch path so the counters exist before use. */
    public static void init() {}
}
