package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Tallies for the {@code StormCellUnloadBudget} pass on {@code ServerMap.postupdate()}, exposed via
 * scrape-time callbacks.
 *
 * <p>The tallies are plain (non-atomic) {@code long}s, same discipline as {@link
 * PlayerLosFastPathMetrics}: the writer runs once per server tick on the main thread ({@code
 * StormCellUnloadBudget.run} accumulates per-call locals and flushes once per call); the scrape
 * thread reads dirty, which is acceptable for monotonic counters.
 */
public final class CellUnloadBudgetMetrics {

    /** Main-thread writers only — see class doc before adding call sites off the main thread. */
    public static long unloadedCells;

    public static long deferredCells;

    @SuppressWarnings("unused")
    private static final CounterWithCallback CELLS =
            CounterWithCallback.builder()
                    .name("pz_server_cell_unloads_total")
                    .help(
                            "Stale server cells handled by the budgeted ServerMap.postupdate pass:"
                                    + " unloaded = destructively unloaded this tick within the"
                                    + " Storm.CellUnloadBudgetPerTick budget; deferred = stale but"
                                    + " left in loadedCells for a later tick because the budget was"
                                    + " exhausted. Only counts while the budgeted pass is active"
                                    + " (budget > 0, cell warming off, no failure latch).")
                    .labelNames("outcome")
                    .callback(
                            callback -> {
                                callback.call((double) unloadedCells, "unloaded");
                                callback.call((double) deferredCells, "deferred");
                            })
                    .register(StormPrometheus.registry());

    private CellUnloadBudgetMetrics() {}

    /** One budgeted postupdate call completed; arguments are its per-call tallies. */
    public static void record(long unloaded, long deferred) {
        unloadedCells += unloaded;
        deferredCells += deferred;
    }
}
