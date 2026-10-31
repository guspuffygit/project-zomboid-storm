package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Metrics for the {@code GameEntityManager.UnregisterEntity} id collision guard. Main-thread writer
 * only (world objects unregister on the game main loop).
 */
public final class EntityIdCollisionMetrics {

    private static long collisions;
    private static long missingEntries;

    @SuppressWarnings("unused")
    private static final CounterWithCallback COLLISIONS =
            CounterWithCallback.builder()
                    .name("storm_entity_id_collisions_total")
                    .help(
                            "World objects that left the entity manager while another object held"
                                    + " their entity id. Vanilla throws Stored entity mismatch here"
                                    + " and takes the other object's entry with it; Storm keeps the"
                                    + " entry. Each increment is logged at WARN with the square.")
                    .callback(callback -> callback.call((double) collisions))
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback MISSING_ENTRIES =
            CounterWithCallback.builder()
                    .name("storm_entity_id_missing_entries_total")
                    .help(
                            "World objects that left the entity manager with no map entry under"
                                    + " their id. Vanilla silently leaves these in the engine until"
                                    + " a restart; Storm removes them.")
                    .callback(callback -> callback.call((double) missingEntries))
                    .register(StormPrometheus.registry());

    private EntityIdCollisionMetrics() {}

    public static void recordCollision() {
        collisions++;
    }

    public static void recordMissingEntry() {
        missingEntries++;
    }
}
