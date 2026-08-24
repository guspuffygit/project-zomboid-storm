package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Tallies for the {@code StormZombieAuthScan} fast path on {@code
 * NetworkZombieManager.updateAuth(IsoZombie)}, exposed via scrape-time callbacks.
 *
 * <p>The tallies are plain (non-atomic) {@code long}s, same discipline as {@link
 * FluidContainerUpdateMetrics}: all writers run on the server main thread (inside {@code
 * NetworkZombiePacker.postupdate()}), and the scrape thread reads dirty, which is acceptable for
 * monotonic counters. Deliberately no per-zombie histogram — the fast path exists partly because
 * per-zombie native-histogram observations were themselves a measurable tick cost.
 */
public final class ZombieAuthScanMetrics {

    /** Main-thread writers only — see class doc before adding call sites off the main thread. */
    public static long optimizedPasses;

    public static long vanillaPasses;
    public static long gateClosedZombies;
    public static long branchMovedZombies;
    public static long scanUnchangedZombies;
    public static long scanMovedZombies;

    @SuppressWarnings("unused")
    private static final CounterWithCallback PASSES =
            CounterWithCallback.builder()
                    .name("pz_zombie_auth_scan_passes_total")
                    .help(
                            "NetworkZombiePacker.updateAuth() passes by executed path: optimized ="
                                    + " the StormZombieAuthScan snapshot was built and per-zombie"
                                    + " calls took the fast path; vanilla = per-zombie calls fell"
                                    + " through to the vanilla body (kill switch off, failure"
                                    + " latch, or rotate-ownership server option).")
                    .labelNames("path")
                    .callback(
                            callback -> {
                                callback.call((double) optimizedPasses, "optimized");
                                callback.call((double) vanillaPasses, "vanilla");
                            })
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback ZOMBIES =
            CounterWithCallback.builder()
                    .name("pz_zombie_auth_scan_zombies_total")
                    .help(
                            "Zombies handled by the StormZombieAuthScan fast path, by outcome:"
                                    + " gate_closed = 2s rescan gate still closed (no work);"
                                    + " branch_moved = grapple/target early-out called moveZombie;"
                                    + " scan_unchanged = relevance scan ran, ownership unchanged,"
                                    + " no-op moveZombie skipped; scan_moved = relevance scan ran"
                                    + " and moveZombie was called.")
                    .labelNames("outcome")
                    .callback(
                            callback -> {
                                callback.call((double) gateClosedZombies, "gate_closed");
                                callback.call((double) branchMovedZombies, "branch_moved");
                                callback.call((double) scanUnchangedZombies, "scan_unchanged");
                                callback.call((double) scanMovedZombies, "scan_moved");
                            })
                    .register(StormPrometheus.registry());

    private ZombieAuthScanMetrics() {}
}
