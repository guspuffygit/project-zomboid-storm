package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.pzstorm.storm.vehicles.StormVehicleAlphaCheckSkip;

/** Metrics for {@code StormVehicleAlphaCheckSkip}. Registered on first class use. */
public final class VehicleAlphaCheckSkipMetrics {

    @SuppressWarnings("unused")
    private static final CounterWithCallback SKIPS =
            CounterWithCallback.builder()
                    .name("storm_vehicle_alpha_check_skips_total")
                    .help(
                            "BaseVehicle.couldSeeIntersectedSquare calls skipped on the dedicated"
                                    + " server (its only consumer, setTargetAlpha, is a server"
                                    + " no-op). One per loaded vehicle per tick while enabled.")
                    .callback(callback -> callback.call((double) StormVehicleAlphaCheckSkip.skips))
                    .register(StormPrometheus.registry());

    private VehicleAlphaCheckSkipMetrics() {}

    /** Forces registration; call once from the patch path so the counter exists before use. */
    public static void init() {}
}
