package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import zombie.network.ServerMap;
import zombie.popman.animal.AnimalInstanceManager;

/**
 * Exports occupancy of the two {@code zombie.network.IsoObjectID} pools that back server-side
 * networked entity IDs:
 *
 * <ul>
 *   <li>{@code storm_zombie_id_pool_size} — live entries in {@code ServerMap.instance.zombieMap}.
 *   <li>{@code storm_animal_id_pool_size} — live entries in {@code
 *       AnimalInstanceManager.AnimalMap}.
 * </ul>
 *
 * <p>Both pools share the 16-bit short address space (65 535 usable slots after the {@code -1}
 * sentinel). The probe-for-free patch installed by {@code IsoObjectIDAllocateFixPatch} prevents
 * silent overwrites once the pool is full, but the upstream symptom — pool nearing exhaustion — is
 * still operationally interesting. Watch these gauges to know when {@code allocateID()} is about to
 * start returning {@code -1} and callers begin aborting spawns.
 *
 * <p>Loaded indirectly via {@code IsoObjectIDAllocateFixPatch}'s static initializer (which calls
 * {@link #ensureStarted()}), so registration is gated server-only along with the patch itself.
 * Callbacks are pulled at scrape time, so referencing {@link ServerMap} / {@link
 * AnimalInstanceManager} here does not force-load them at patch-registration time.
 */
public final class IsoObjectIdPoolMetrics {

    private static final GaugeWithCallback ZOMBIE_POOL_SIZE =
            GaugeWithCallback.builder()
                    .name("storm_zombie_id_pool_size")
                    .help(
                            "Number of live entries in ServerMap.zombieMap (zombie.network.IsoObjectID)."
                                    + " 65535 usable slots — exhaustion causes allocateID to return -1.")
                    .callback(cb -> cb.call(zombiePoolSize()))
                    .register(StormPrometheus.registry());

    private static final GaugeWithCallback ANIMAL_POOL_SIZE =
            GaugeWithCallback.builder()
                    .name("storm_animal_id_pool_size")
                    .help(
                            "Number of live entries in AnimalInstanceManager.AnimalMap (zombie.network.IsoObjectID)."
                                    + " 65535 usable slots — exhaustion causes allocateID to return -1.")
                    .callback(cb -> cb.call(animalPoolSize()))
                    .register(StormPrometheus.registry());

    private IsoObjectIdPoolMetrics() {}

    /** No-op; calling forces class load so the static initializer fires. */
    public static void ensureStarted() {}

    private static int zombiePoolSize() {
        ServerMap sm = ServerMap.instance;
        if (sm == null || sm.zombieMap == null) {
            return 0;
        }
        return sm.zombieMap.size();
    }

    private static int animalPoolSize() {
        AnimalInstanceManager aim = AnimalInstanceManager.getInstance();
        if (aim == null || aim.getAnimals() == null) {
            return 0;
        }
        return aim.getAnimals().size();
    }
}
