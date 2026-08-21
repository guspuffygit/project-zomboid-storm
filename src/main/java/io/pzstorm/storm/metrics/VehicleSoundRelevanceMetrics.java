package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;

/**
 * Metrics for {@code StormVehicleSoundRelevance}. Main-thread writers only ({@code
 * Manager.update()} runs on the server main loop).
 */
public final class VehicleSoundRelevanceMetrics {

    public static long connectionsFast;
    public static long connectionsVanilla;
    private static long snapshots;
    private static long failures;

    private static final Histogram SNAPSHOT_DURATION =
            Histogram.builder()
                    .name("storm_vehicle_sound_relevance_snapshot_duration_seconds")
                    .help(
                            "Time to evaluate the audible radius of every loaded vehicle once at"
                                    + " the start of each vehicleNetworkSound Manager.update().")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Gauge SCANNED_VEHICLES =
            Gauge.builder()
                    .name("storm_vehicle_sound_relevance_scanned_vehicles")
                    .help("Loaded vehicles walked by the last per-tick audible-radius snapshot.")
                    .register(StormPrometheus.registry());

    private static final Gauge NOISY_VEHICLES =
            Gauge.builder()
                    .name("storm_vehicle_sound_relevance_noisy_vehicles")
                    .help(
                            "Vehicles with a non-zero audible radius (alarm, beeper, door alarm,"
                                    + " engine not idle, horn, siren) in the last snapshot — the"
                                    + " only ones tested per connection.")
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback CONNECTIONS =
            CounterWithCallback.builder()
                    .name("storm_vehicle_sound_relevance_connections_total")
                    .help(
                            "getVehiclesRelevantToConnection calls by path: fast = answered from"
                                    + " the per-tick snapshot; vanilla = the vanilla per-connection"
                                    + " whole-vehicle-set scan ran (disabled, latched off, or no"
                                    + " snapshot).")
                    .labelNames("path")
                    .callback(
                            callback -> {
                                callback.call((double) connectionsFast, "fast");
                                callback.call((double) connectionsVanilla, "vanilla");
                            })
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback SNAPSHOTS =
            CounterWithCallback.builder()
                    .name("storm_vehicle_sound_relevance_snapshots_total")
                    .help(
                            "Per-tick snapshots by outcome; a failed outcome latches the fast path"
                                    + " off for the session.")
                    .labelNames("outcome")
                    .callback(
                            callback -> {
                                callback.call((double) snapshots, "ok");
                                callback.call((double) failures, "failed");
                            })
                    .register(StormPrometheus.registry());

    private VehicleSoundRelevanceMetrics() {}

    public static void recordSnapshot(int scanned, int noisy, long nanos) {
        snapshots++;
        SCANNED_VEHICLES.set(scanned);
        NOISY_VEHICLES.set(noisy);
        SNAPSHOT_DURATION.observe(nanos / 1e9);
    }

    public static void recordFailure() {
        failures++;
    }

    public static long failures() {
        return failures;
    }
}
