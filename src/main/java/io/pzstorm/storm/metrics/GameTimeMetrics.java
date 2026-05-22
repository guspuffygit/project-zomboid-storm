package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import zombie.GameTime;

/**
 * Exports the current in-game calendar as Prometheus gauges. Values are pulled at scrape time from
 * {@link GameTime#getInstance()}, so they always reflect the latest state without needing an event
 * push.
 *
 * <p>PZ stores month and day zero-indexed internally; the exported values are shifted to 1-12 and
 * 1-31 so they match what a human reads on the in-game calendar.
 *
 * <p>Loaded indirectly via {@link EventDispatchMetrics}'s static initializer (which calls {@link
 * #ensureStarted()}), so the callback registers the moment the first event dispatches. Mirrors the
 * piggyback pattern used by {@link ThreadAllocBytesMetrics}.
 */
public final class GameTimeMetrics {

    private static final GaugeWithCallback YEAR =
            GaugeWithCallback.builder()
                    .name("pz_game_year")
                    .help("Current in-game calendar year.")
                    .callback(cb -> cb.call(GameTime.getInstance().getYear()))
                    .register(StormPrometheus.registry());

    private static final GaugeWithCallback MONTH =
            GaugeWithCallback.builder()
                    .name("pz_game_month")
                    .help("Current in-game month (1-12).")
                    .callback(cb -> cb.call(GameTime.getInstance().getMonth() + 1))
                    .register(StormPrometheus.registry());

    private static final GaugeWithCallback DAY =
            GaugeWithCallback.builder()
                    .name("pz_game_day")
                    .help("Current in-game day of month (1-31).")
                    .callback(cb -> cb.call(GameTime.getInstance().getDay() + 1))
                    .register(StormPrometheus.registry());

    private GameTimeMetrics() {}

    /** No-op; calling forces class load so the static initializer fires. */
    public static void ensureStarted() {}
}
