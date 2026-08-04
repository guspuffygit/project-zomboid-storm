package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Call tally for {@code IsoPlayer.TestZombieSpotPlayer} on the server, exposed as {@code
 * pz_zombie_spot_player_calls_total} via a scrape-time callback.
 *
 * <p>Replaces the previous native histogram {@code pz_zombie_spot_player_call_duration_seconds}:
 * timing every call cost two {@code System.nanoTime()} reads plus a histogram observation at
 * per-player × per-moving-object cardinality, which the main-thread profile flagged. Aggregate
 * spot-path duration is still visible through the {@code updateLOS} step timings. The tally is a
 * plain main-thread-only {@code long} read dirty at scrape time (see {@link
 * ServerLOSIsCouldSeeMetrics} for the rationale).
 */
public final class ZombieSpotPlayerMetrics {

    /** Main-thread writers only. */
    public static long calls;

    @SuppressWarnings("unused")
    private static final CounterWithCallback CALLS =
            CounterWithCallback.builder()
                    .name("pz_zombie_spot_player_calls_total")
                    .help("IsoPlayer.TestZombieSpotPlayer invocations on the server.")
                    .callback(callback -> callback.call((double) calls))
                    .register(StormPrometheus.registry());

    private ZombieSpotPlayerMetrics() {}

    public static void recordCall() {
        calls++;
    }
}
