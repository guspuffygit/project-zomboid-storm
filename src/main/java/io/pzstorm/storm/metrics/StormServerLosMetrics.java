package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.prometheus.metrics.core.metrics.Histogram;
import io.pzstorm.storm.los.StormServerLosConfig;

/** Metrics for the parallel ServerLOS engine. Registered (and only fed) on the dedicated server. */
public final class StormServerLosMetrics {

    private static final Histogram TICK =
            Histogram.builder()
                    .name("storm_serverlos_tick_seconds")
                    .help("Wall time of one parallel ServerLOS tick (batch dispatch + join).")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram CALCLOS =
            Histogram.builder()
                    .name("storm_serverlos_calclos_seconds")
                    .help("Per-player LOS grid scan time, labelled by worker slot.")
                    .labelNames("slot")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram BATCH =
            Histogram.builder()
                    .name("storm_serverlos_batch_players")
                    .help("Number of players processed in a single LOS tick.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter ONSEE_LOCKED =
            Counter.builder()
                    .name("storm_serverlos_onsee_locked_total")
                    .help("IsoRoom.onSee invocations serialized under the parallel-LOS lock.")
                    .register(StormPrometheus.registry());

    private static final GaugeWithCallback THREADS =
            GaugeWithCallback.builder()
                    .name("storm_serverlos_threads")
                    .help("Configured ServerLOS worker count (concurrent player scans).")
                    .callback(cb -> cb.call(StormServerLosConfig.threads()))
                    .register(StormPrometheus.registry());

    private StormServerLosMetrics() {}

    public static void recordTick(long nanos, int players) {
        TICK.observe(nanos / 1e9);
        BATCH.observe(players);
    }

    public static void recordCalcLos(long nanos, int slot) {
        CALCLOS.labelValues(Integer.toString(slot)).observe(nanos / 1e9);
    }

    public static void recordOnSeeLocked() {
        ONSEE_LOCKED.inc();
    }
}
