package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

/**
 * Server tick-rate (TPS) and per-tick duration.
 *
 * <p>One observation is recorded per server update tick (one {@code GameServer.main} frame step,
 * gated to the target 10 FPS by the server update limiter). The dedicated-server-only hook lives in
 * {@code io.pzstorm.storm.advice.servertick.ServerTickAdvice}, which is registered server-only
 * because the hooked method ({@code StatisticManager.update(long)}) also runs on the client via
 * {@code GameClient}.
 *
 * <p>This is the honest counterpart to PZ's {@code performance{parameter="fps"}} gauge, which is
 * the inter-tick cycle duration mislabeled as "fps", exported only every {@code
 * multiplayerStatisticsPeriod} seconds and averaged with a buggy decay. These instruments record
 * every tick, unthrottled.
 *
 * <ul>
 *   <li>{@code rate(storm_server_tick_total[1m])} &mdash; ticks per second (TPS). Target 10; drops
 *       below 10 when the server cannot complete its work inside the 100&nbsp;ms tick budget.
 *   <li>{@code histogram_quantile(0.99, rate(storm_server_tick_duration_seconds[5m]))} &mdash; how
 *       long a single tick takes end-to-end (wall-clock cycle time). ~0.1&nbsp;s when healthy; p99
 *       spikes mark stalls (GC pauses, heavy ticks).
 * </ul>
 */
public final class ServerTickMetrics {

    private static final Counter TICKS =
            Counter.builder()
                    .name("storm_server_tick_total")
                    .help(
                            "Total server update ticks (GameServer frame steps). rate() is the"
                                    + " server's ticks per second (TPS); target 10.")
                    .register(StormPrometheus.registry());

    private static final Histogram TICK_DURATION =
            Histogram.builder()
                    .name("storm_server_tick_duration_seconds")
                    .help(
                            "Wall-clock duration of a single server tick (cycle time between"
                                    + " GameServer frame steps). Equals 1/TPS; ~0.1s at the healthy"
                                    + " 10 TPS target, climbs when the server falls behind.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private ServerTickMetrics() {}

    /**
     * Record one completed server tick.
     *
     * @param millis wall-clock milliseconds since the previous tick (PZ's {@code dif}).
     */
    public static void recordTick(long millis) {
        TICKS.inc();
        TICK_DURATION.observe(millis / 1e3);
    }
}
