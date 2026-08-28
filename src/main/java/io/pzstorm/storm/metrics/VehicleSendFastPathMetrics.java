package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.pzstorm.storm.vehicles.StormVehicleSend;

/**
 * Metrics for {@code StormVehicleSend}. Main-thread writers only ({@code sendVehicles} runs on the
 * server main loop).
 */
public final class VehicleSendFastPathMetrics {

    private static long failures;

    @SuppressWarnings("unused")
    private static final CounterWithCallback CANDIDATES =
            CounterWithCallback.builder()
                    .name("storm_vehicle_send_fast_path_candidates_total")
                    .help(
                            "Vehicles examined by the spatially pre-filtered"
                                    + " VehicleManager.sendVehicles pass (post grid query, before"
                                    + " the exact relevance test). Compare against loaded vehicles"
                                    + " x connections x 10/s for the vanilla pair-check count this"
                                    + " path avoids.")
                    .callback(callback -> callback.call((double) StormVehicleSend.candidates))
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback SENDS =
            CounterWithCallback.builder()
                    .name("storm_vehicle_send_fast_path_sends_total")
                    .help(
                            "Vehicle update/full-update packets sent by the fast-path"
                                    + " VehicleManager.sendVehicles pass.")
                    .callback(callback -> callback.call((double) StormVehicleSend.sends))
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback FAILURES =
            CounterWithCallback.builder()
                    .name("storm_vehicle_send_fast_path_failures_total")
                    .help(
                            "Throwables from the fast-path VehicleManager.sendVehicles pass. Any"
                                    + " failure latches the fast path off for the session.")
                    .callback(callback -> callback.call((double) failures))
                    .register(StormPrometheus.registry());

    private VehicleSendFastPathMetrics() {}

    public static void recordFailure() {
        failures++;
    }

    /** Forces registration; call once from the patch path so the counters exist before use. */
    public static void init() {}
}
