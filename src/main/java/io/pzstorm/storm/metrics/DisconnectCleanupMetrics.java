package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class DisconnectCleanupMetrics {

    private static final Histogram REMOVE_ZOMBIES =
            Histogram.builder()
                    .name("pz_network_zombie_manager_remove_zombies_call_duration_seconds")
                    .help("Duration of NetworkZombieManager.removeZombies advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram REMOVE_ANIMALS =
            Histogram.builder()
                    .name("pz_animal_instance_manager_remove_animals_call_duration_seconds")
                    .help("Duration of AnimalInstanceManager.removeAnimals advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram REMOVE_DEAD_BODIES =
            Histogram.builder()
                    .name("pz_iso_dead_body_remove_dead_bodies_call_duration_seconds")
                    .help("Duration of IsoDeadBody.removeDeadBodies advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram REMOVE_VEHICLES =
            Histogram.builder()
                    .name("pz_vehicle_manager_remove_vehicles_call_duration_seconds")
                    .help("Duration of VehicleManager.removeVehicles advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private DisconnectCleanupMetrics() {}

    public static void recordRemoveZombiesNanos(long nanos) {
        REMOVE_ZOMBIES.observe(nanos / 1e9);
    }

    public static void recordRemoveAnimalsNanos(long nanos) {
        REMOVE_ANIMALS.observe(nanos / 1e9);
    }

    public static void recordRemoveDeadBodiesNanos(long nanos) {
        REMOVE_DEAD_BODIES.observe(nanos / 1e9);
    }

    public static void recordRemoveVehiclesNanos(long nanos) {
        REMOVE_VEHICLES.observe(nanos / 1e9);
    }
}
