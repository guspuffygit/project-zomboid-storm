package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;

/**
 * Outcome telemetry for the world-wide zombie ceiling ({@code
 * io.pzstorm.storm.zombie.StormZombieTotalCap}).
 *
 * <ul>
 *   <li>{@code storm_zombies_total_cap_culled_total} — zombies removed by the cap.
 * </ul>
 *
 * <p>Pair the rate of this counter with {@code storm_zombie_id_pool_size} (live zombie total) and
 * {@code storm_max_total_zombies} (the configured ceiling). A brief burst after a population spike
 * is the cap doing its job; a sustained non-zero rate means the population settings want more
 * zombies than the cap permits and the two are fighting — lower {@code
 * ZombieConfig.PopulationMultiplier} rather than raising the cap.
 */
public final class StormZombieCapMetrics {

    private static final Counter ZOMBIES_CULLED =
            Counter.builder()
                    .name("storm_zombies_total_cap_culled_total")
                    .help(
                            "Zombies deleted by Storm's world-wide zombie ceiling"
                                    + " (Storm.MaxTotalZombies). Only counts zombies that were"
                                    + " outside, had no target, were not reanimated players, and"
                                    + " were beyond every connection's relevance radius.")
                    .register(StormPrometheus.registry());

    private StormZombieCapMetrics() {}

    /**
     * Forces this class to initialise so the counter appears in {@code /metrics} reading {@code 0}.
     * Without it the only reference is inside the cull loop, so a server that never exceeds the cap
     * would omit the series entirely and {@code rate()} queries over it would return no data
     * instead of zero.
     */
    public static void register() {
        ZOMBIES_CULLED.inc(0);
    }

    public static void recordCulled(int count) {
        ZOMBIES_CULLED.inc(count);
    }
}
