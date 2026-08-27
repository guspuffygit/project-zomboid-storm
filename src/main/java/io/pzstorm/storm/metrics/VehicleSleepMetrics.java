package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.pzstorm.storm.vehicles.StormVehicleSleep;

/** Metrics for {@code StormVehicleSleep}. Registered on first class use. */
public final class VehicleSleepMetrics {

    @SuppressWarnings("unused")
    private static final CounterWithCallback SKIPS =
            CounterWithCallback.builder()
                    .name("storm_vehicle_sleep_skips_total")
                    .help(
                            "BaseVehicle.update calls skipped because the vehicle was asleep"
                                    + " (parked, inert, throttled to one full update every"
                                    + " storm.vehicle.sleepTicks ticks).")
                    .callback(callback -> callback.call((double) StormVehicleSleep.skips))
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback FULL_UPDATES =
            CounterWithCallback.builder()
                    .name("storm_vehicle_sleep_full_updates_total")
                    .help(
                            "BaseVehicle.update calls that ran the vanilla body (awake vehicles"
                                    + " plus sleeping vehicles on their staggered full tick).")
                    .callback(callback -> callback.call((double) StormVehicleSleep.fullUpdates))
                    .register(StormPrometheus.registry());

    private VehicleSleepMetrics() {}

    /** Forces registration; call once from the patch path so the counters exist before use. */
    public static void init() {}
}
