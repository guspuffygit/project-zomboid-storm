package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Tallies for the idle-wander interval decision in {@code ZombieRainWanderInterval.adjust}, exposed
 * via a scrape-time callback.
 *
 * <p>Plain (non-atomic) {@code long}s, same discipline as {@link PlayerLosFastPathMetrics}: the
 * writers are the zombie AI states on the main thread, the scrape thread reads dirty, which is
 * acceptable for monotonic counters.
 *
 * <p>Only counted while {@code Storm.ZombieRainWanderPercent} is off its vanilla default; at the
 * default the decision returns before it asks the weather anything. That makes {@code rain_scaled}
 * the answer to "is this option actually doing something": it can only move while it is raining on
 * a server with the option set, and a storm that leaves it at zero means the patch is not reaching
 * the picks.
 */
public final class ZombieRainWanderMetrics {

    /** Main-thread writers only. */
    public static long rainScaled;

    public static long dry;

    @SuppressWarnings("unused")
    private static final CounterWithCallback PICKS =
            CounterWithCallback.builder()
                    .name("pz_zombie_wander_picks_total")
                    .help(
                            "Idle-zombie wander intervals picked while"
                                    + " Storm.ZombieRainWanderPercent is off its vanilla default:"
                                    + " rain_scaled = it was raining and the interval was"
                                    + " lengthened by the configured percentage; dry = it was not"
                                    + " raining (or the precipitation was snow, which vanilla does"
                                    + " not count as rain) and vanilla's own 1.5x dry lengthening"
                                    + " was left alone. Not counted at the vanilla default.")
                    .labelNames("outcome")
                    .callback(
                            callback -> {
                                callback.call((double) rainScaled, "rain_scaled");
                                callback.call((double) dry, "dry");
                            })
                    .register(StormPrometheus.registry());

    private ZombieRainWanderMetrics() {}

    /** Test-only: zeroes the tallies so a case can assert on its own writes. */
    public static void resetForTest() {
        rainScaled = 0;
        dry = 0;
    }
}
