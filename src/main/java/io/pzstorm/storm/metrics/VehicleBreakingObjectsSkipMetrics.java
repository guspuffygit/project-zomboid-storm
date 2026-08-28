package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.pzstorm.storm.vehicles.StormVehicleBreakingObjectsSkip;

/** Metrics for {@code StormVehicleBreakingObjectsSkip}. Registered on first class use. */
public final class VehicleBreakingObjectsSkipMetrics {

    @SuppressWarnings("unused")
    private static final CounterWithCallback SKIPS =
            CounterWithCallback.builder()
                    .name("storm_vehicle_breaking_objects_skips_total")
                    .help(
                            "BaseVehicle.breakingObjects calls skipped on the dedicated server"
                                    + " because the vehicle (and its tow chain) has no driver. One"
                                    + " per driverless engine-running vehicle per tick while"
                                    + " enabled.")
                    .callback(
                            callback ->
                                    callback.call((double) StormVehicleBreakingObjectsSkip.skips))
                    .register(StormPrometheus.registry());

    private VehicleBreakingObjectsSkipMetrics() {}

    /** Forces registration; call once from the patch path so the counter exists before use. */
    public static void init() {}
}
