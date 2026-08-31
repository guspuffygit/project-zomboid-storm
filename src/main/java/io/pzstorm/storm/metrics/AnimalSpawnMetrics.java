package io.pzstorm.storm.metrics;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import zombie.characters.animals.VirtualAnimal;
import zombie.core.math.PZMath;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

/**
 * Instruments the server-side animal population pipeline end to end, so "are animals respawning?"
 * can be answered from Prometheus instead of by eyeballing the world.
 *
 * <h2>How vanilla populates animals</h2>
 *
 * <p>There are exactly two sources of new wild/ranch animals, and both are one-shot per location:
 *
 * <ol>
 *   <li><b>Migration zones.</b> {@code AnimalCell.load()} spawns only when the cell has no {@code
 *       apop_x_y.bin} on disk ({@code fileLoaded == false}); it then calls {@code
 *       AnimalZones.spawnAnimalsInCell} → {@code spawnAnimalsOnZone} for every {@code AnimalZone}
 *       in the cell. That method is a no-op once {@code zone.spawnedAnimals} is set, and the flag
 *       is persisted with the zone. <b>There is no periodic wild respawn in vanilla</b> — a cell
 *       whose animals have been hunted out stays empty. A server that "stopped respawning animals"
 *       is usually a server whose cells have all been visited once.
 *   <li><b>Ranch stories.</b> {@code IsoChunk.AddRanchAnimals} → {@code
 *       RandomizedRanchBase.checkRanchStory}, which rolls {@code AnimalRanchChance} the first time
 *       a {@code Ranch} zone is fully streamed ({@code hourLastSeen == 0}).
 * </ol>
 *
 * <p>Everything else is movement, not creation: animals round-trip between real {@code IsoAnimal}s
 * and parked {@code VirtualAnimal}s as chunks load and unload ({@code
 * AnimalManagerWorker.addAnimal} out, {@code AnimalManagerMain.fromWorker} back in). Those two
 * seams are instrumented as well, because the common failure mode is not "nothing spawned" but
 * "animals exist as virtual groups and never come back into the world".
 *
 * <h2>Reading the metrics</h2>
 *
 * <table>
 *   <tr><td>{@code pz_animal_cell_load_total{result="fresh"}} flat at 0</td>
 *       <td>every animal cell already has a save file — no zone spawn will ever run again. Expected
 *       on a long-lived map; this is the usual "respawn is broken" false alarm.</td></tr>
 *   <tr><td>{@code pz_animal_zone_spawn_total{result="already_spawned"}} dominating</td>
 *       <td>zones are being re-evaluated but have already fired once.</td></tr>
 *   <tr><td>{@code result="disabled"}</td>
 *       <td>the zone carries {@code SpawnAnimals=false} in its map properties.</td></tr>
 *   <tr><td>{@code result="no_animals"}</td>
 *       <td>the zone fired but produced no virtual group — bad {@code AnimalType} / missing
 *       migration group definition, or the polyline point lookup failed.</td></tr>
 *   <tr><td>{@code pz_animal_realize_total{result="no_square"}} rising</td>
 *       <td>virtual groups are being handed back to the world at coordinates with no loaded grid
 *       square; vanilla drops them on the floor and they stay parked.</td></tr>
 *   <tr><td>{@code pz_animal_virtual_animals} rising while {@code storm_animal_id_pool_size}
 *       falls</td>
 *       <td>the population is alive but stuck in the virtual half of the round trip.</td></tr>
 * </table>
 *
 * <p>The live (real) animal count is already exported as {@code storm_animal_id_pool_size} by
 * {@link IsoObjectIdPoolMetrics}; this class deliberately does not duplicate it.
 *
 * <h2>Reflection</h2>
 *
 * <p>Several of the fields this class reads ({@code AnimalZone.spawnAnimal}, {@code
 * AnimalZone.spawnedAnimals}, {@code VirtualAnimal.animals}, {@code AnimalZones.chunksWithTracks},
 * {@code AnimalChunk.animals}) are package-private, so they are read reflectively with cached
 * {@link Field} handles. Every lookup is fail-soft: a resolution failure logs once and degrades
 * that one series (skip / {@code unknown} label), never throwing into game code. All call sites are
 * cold — cell load, chunk load, scrape — so the reflection cost is irrelevant.
 */
public final class AnimalSpawnMetrics {

    private static final Counter ZONE_SPAWNS =
            Counter.builder()
                    .name("pz_animal_zone_spawn_total")
                    .help(
                            "AnimalZones.spawnAnimalsOnZone evaluations by outcome."
                                    + " spawned = a virtual animal group was created;"
                                    + " no_animals = eligible but produced nothing;"
                                    + " already_spawned / disabled / not_follow = skipped by vanilla.")
                    .labelNames("result")
                    .register(StormPrometheus.registry());

    private static final CounterDataPoint ZONE_SPAWNED = ZONE_SPAWNS.labelValues("spawned");
    private static final CounterDataPoint ZONE_NO_ANIMALS = ZONE_SPAWNS.labelValues("no_animals");
    private static final CounterDataPoint ZONE_ALREADY = ZONE_SPAWNS.labelValues("already_spawned");
    private static final CounterDataPoint ZONE_DISABLED = ZONE_SPAWNS.labelValues("disabled");
    private static final CounterDataPoint ZONE_NOT_FOLLOW = ZONE_SPAWNS.labelValues("not_follow");
    private static final CounterDataPoint ZONE_UNKNOWN = ZONE_SPAWNS.labelValues("unknown");

    private static final Counter ZONE_SPAWN_ANIMALS =
            Counter.builder()
                    .name("pz_animal_zone_spawn_animals_total")
                    .help(
                            "IsoAnimals contained in the virtual groups created by migration-zone spawns.")
                    .register(StormPrometheus.registry());

    private static final Counter CELL_LOADS =
            Counter.builder()
                    .name("pz_animal_cell_load_total")
                    .help(
                            "AnimalCell.load() calls by outcome. from_file = apop_x_y.bin existed;"
                                    + " fresh = no save file, so migration-zone spawning ran for that cell.")
                    .labelNames("result")
                    .register(StormPrometheus.registry());

    private static final CounterDataPoint CELL_FROM_FILE = CELL_LOADS.labelValues("from_file");
    private static final CounterDataPoint CELL_FRESH = CELL_LOADS.labelValues("fresh");

    private static final Counter VIRTUAL_REGISTERED =
            Counter.builder()
                    .name("pz_animal_virtual_registered_total")
                    .help(
                            "Virtual animal groups handed to AnimalManagerWorker.addAnimal by source."
                                    + " zone_spawn = newly spawned by a migration zone;"
                                    + " requeue = an existing group parked again (chunk unload,"
                                    + " virtualization, meta movement).")
                    .labelNames("source")
                    .register(StormPrometheus.registry());

    private static final CounterDataPoint VIRTUAL_FROM_ZONE =
            VIRTUAL_REGISTERED.labelValues("zone_spawn");
    private static final CounterDataPoint VIRTUAL_REQUEUE =
            VIRTUAL_REGISTERED.labelValues("requeue");

    private static final Counter REALIZE =
            Counter.builder()
                    .name("pz_animal_realize_total")
                    .help(
                            "Virtual animal groups passed to AnimalManagerMain.fromWorker by outcome."
                                    + " realized = the target grid square was loaded and the group"
                                    + " became real IsoAnimals; no_square = vanilla silently dropped it.")
                    .labelNames("result")
                    .register(StormPrometheus.registry());

    private static final CounterDataPoint REALIZE_OK = REALIZE.labelValues("realized");
    private static final CounterDataPoint REALIZE_NO_SQUARE = REALIZE.labelValues("no_square");

    private static final Counter REALIZE_ANIMALS =
            Counter.builder()
                    .name("pz_animal_realize_animals_total")
                    .help("IsoAnimals put back into the world by AnimalManagerMain.fromWorker.")
                    .register(StormPrometheus.registry());

    private static final Counter RANCH_CHECKS =
            Counter.builder()
                    .name("pz_animal_ranch_check_total")
                    .help(
                            "RandomizedRanchBase.checkRanchStory calls by outcome. processed = a fully"
                                    + " streamed, never-seen Ranch zone was evaluated (the ranch chance was"
                                    + " rolled); skipped = not a ranch, not streamed, or already seen.")
                    .labelNames("result")
                    .register(StormPrometheus.registry());

    private static final CounterDataPoint RANCH_PROCESSED = RANCH_CHECKS.labelValues("processed");
    private static final CounterDataPoint RANCH_SKIPPED = RANCH_CHECKS.labelValues("skipped");

    private static final Counter RANCH_SPAWNS =
            Counter.builder()
                    .name("pz_animal_ranch_spawn_total")
                    .help(
                            "Ranch zones that won the AnimalRanchChance roll and were populated"
                                    + " (RandomizedRanchBase.randomizeRanch).")
                    .register(StormPrometheus.registry());

    private static final Counter DEATHS =
            Counter.builder()
                    .name("pz_animal_deaths_total")
                    .help(
                            "Animal deaths seen by Storm's deduplicated death seam (every vanilla death"
                                    + " path, including the hutch and inventory-kill bypasses).")
                    .register(StormPrometheus.registry());

    private static final GaugeWithCallback VIRTUAL_GROUPS =
            GaugeWithCallback.builder()
                    .name("pz_animal_virtual_groups")
                    .help(
                            "Virtual animal groups currently parked in loaded animal chunks"
                                    + " (AnimalZones.chunksWithTracks). These are animals that exist but are"
                                    + " not in the world.")
                    .callback(cb -> cb.call(census(false)))
                    .register(StormPrometheus.registry());

    private static final GaugeWithCallback VIRTUAL_ANIMALS =
            GaugeWithCallback.builder()
                    .name("pz_animal_virtual_animals")
                    .help(
                            "IsoAnimals held inside the parked virtual groups. Compare against"
                                    + " storm_animal_id_pool_size (the live, in-world animal count).")
                    .callback(cb -> cb.call(census(true)))
                    .register(StormPrometheus.registry());

    /**
     * Set for the duration of a {@code spawnAnimalsOnZone} call that vanilla considers eligible, so
     * {@link #recordVirtualRegistered(Object)} can tell a brand-new group apart from a group being
     * parked again. Holds the number of groups registered during the call.
     */
    private static final ThreadLocal<int[]> ZONE_SPAWN_DEPTH = new ThreadLocal<>();

    private static volatile Field fZoneSpawnAnimal;
    private static volatile Field fZoneSpawnedAnimals;
    private static volatile Field fVirtualAnimals;
    private static volatile Field fChunksWithTracks;
    private static volatile Field fChunkAnimals;

    private static final Set<String> WARNED = Collections.synchronizedSet(new HashSet<>());

    private AnimalSpawnMetrics() {}

    /** No-op whose side effect is loading this class, registering the scrape-time gauges. */
    public static void ensureStarted() {}

    /**
     * {@code AnimalZones.spawnAnimalsOnZone} entry. Classifies the zone the same way vanilla does
     * and records the skip outcomes immediately.
     *
     * @return {@code true} when vanilla will attempt a spawn, in which case the caller must pass
     *     the value to {@link #endZoneSpawn(boolean)}.
     */
    public static boolean beginZoneSpawn(Object zoneObj) {
        if (!GameServer.server || zoneObj == null) {
            return false;
        }
        try {
            Field spawned = zoneSpawnedAnimalsField(zoneObj.getClass());
            Field enabled = zoneSpawnAnimalField(zoneObj.getClass());
            if (spawned == null || enabled == null) {
                ZONE_UNKNOWN.inc();
                return false;
            }
            if (spawned.getBoolean(zoneObj)) {
                ZONE_ALREADY.inc();
                return false;
            }
            if (!enabled.getBoolean(zoneObj)) {
                ZONE_DISABLED.inc();
                return false;
            }
            if (!"Follow".equals(zoneAction(zoneObj))) {
                ZONE_NOT_FOLLOW.inc();
                return false;
            }
            ZONE_SPAWN_DEPTH.set(new int[1]);
            return true;
        } catch (Throwable t) {
            warnOnce("classify animal zone spawn", t);
            ZONE_UNKNOWN.inc();
            return false;
        }
    }

    /** {@code AnimalZones.spawnAnimalsOnZone} exit; {@code eligible} is the value from enter. */
    public static void endZoneSpawn(boolean eligible) {
        if (!eligible) {
            return;
        }
        int[] counter = ZONE_SPAWN_DEPTH.get();
        ZONE_SPAWN_DEPTH.remove();
        if (counter != null && counter[0] > 0) {
            ZONE_SPAWNED.inc();
        } else {
            ZONE_NO_ANIMALS.inc();
        }
    }

    /** {@code AnimalManagerWorker.addAnimal} entry. */
    public static void recordVirtualRegistered(Object virtualAnimalObj) {
        if (!GameServer.server) {
            return;
        }
        int[] counter = ZONE_SPAWN_DEPTH.get();
        if (counter == null) {
            VIRTUAL_REQUEUE.inc();
            return;
        }
        counter[0]++;
        VIRTUAL_FROM_ZONE.inc();
        int animals = groupSize(virtualAnimalObj);
        if (animals > 0) {
            ZONE_SPAWN_ANIMALS.inc(animals);
        }
    }

    /** {@code AnimalCell.load()} exit; {@code fileLoaded} is the field the method just set. */
    public static void recordCellLoad(boolean fileLoaded) {
        if (!GameServer.server) {
            return;
        }
        if (fileLoaded) {
            CELL_FROM_FILE.inc();
        } else {
            CELL_FRESH.inc();
        }
    }

    /**
     * {@code AnimalManagerMain.fromWorker} entry. Repeats vanilla's grid-square lookup for each
     * group so the drop-on-the-floor path (no loaded square at the group's coordinates) is
     * countable; vanilla itself neither logs nor reports it.
     */
    public static void recordRealizeBatch(Object animalsObj) {
        if (!GameServer.server || !(animalsObj instanceof List<?> groups) || groups.isEmpty()) {
            return;
        }
        try {
            for (Object group : groups) {
                if (group == null) {
                    continue;
                }
                if (hasLoadedSquare(group)) {
                    REALIZE_OK.inc();
                    int animals = groupSize(group);
                    if (animals > 0) {
                        REALIZE_ANIMALS.inc(animals);
                    }
                } else {
                    REALIZE_NO_SQUARE.inc();
                }
            }
        } catch (Throwable t) {
            warnOnce("count animal realization", t);
        }
    }

    /** {@code RandomizedRanchBase.checkRanchStory} exit; {@code processed} is its return value. */
    public static void recordRanchCheck(boolean processed) {
        if (!GameServer.server) {
            return;
        }
        if (processed) {
            RANCH_PROCESSED.inc();
        } else {
            RANCH_SKIPPED.inc();
        }
    }

    /** {@code RandomizedRanchBase.randomizeRanch} entry — the chance roll already succeeded. */
    public static void recordRanchSpawn() {
        if (!GameServer.server) {
            return;
        }
        RANCH_SPAWNS.inc();
    }

    /** Called from Storm's deduplicated animal-death seam. */
    public static void recordDeath() {
        if (!GameServer.server) {
            return;
        }
        DEATHS.inc();
    }

    private static boolean hasLoadedSquare(Object group) {
        IsoWorld world = IsoWorld.instance;
        if (world == null
                || world.currentCell == null
                || !(group instanceof VirtualAnimal animal)) {
            return false;
        }
        return world.currentCell.getGridSquare(
                        PZMath.fastfloor(animal.getX()),
                        PZMath.fastfloor(animal.getY()),
                        PZMath.fastfloor(animal.getZ()))
                != null;
    }

    /** Number of {@code IsoAnimal}s inside a {@code VirtualAnimal}, or {@code -1} if unreadable. */
    private static int groupSize(Object virtualAnimalObj) {
        if (virtualAnimalObj == null) {
            return -1;
        }
        try {
            Field f = virtualAnimalsField(virtualAnimalObj.getClass());
            if (f == null) {
                return -1;
            }
            Object list = f.get(virtualAnimalObj);
            return list instanceof ArrayList<?> animals ? animals.size() : -1;
        } catch (Throwable t) {
            warnOnce("read VirtualAnimal.animals", t);
            return -1;
        }
    }

    /**
     * Walks the chunks vanilla tracks as non-empty and totals the parked groups (or the animals
     * inside them when {@code countAnimals}). Held under the same monitor vanilla uses for that set
     * so the walk cannot race a concurrent add/remove.
     */
    private static double census(boolean countAnimals) {
        try {
            Field f = chunksWithTracksField();
            if (f == null) {
                return 0.0;
            }
            Object value = f.get(null);
            if (!(value instanceof Set<?> chunks)) {
                return 0.0;
            }
            long total = 0;
            synchronized (chunks) {
                for (Object chunk : chunks) {
                    if (chunk == null) {
                        continue;
                    }
                    Field animalsField = chunkAnimalsField(chunk.getClass());
                    if (animalsField == null) {
                        return 0.0;
                    }
                    Object list = animalsField.get(chunk);
                    if (!(list instanceof ArrayList<?> groups)) {
                        continue;
                    }
                    if (!countAnimals) {
                        total += groups.size();
                        continue;
                    }
                    for (Object group : groups) {
                        int size = groupSize(group);
                        if (size > 0) {
                            total += size;
                        }
                    }
                }
            }
            return total;
        } catch (Throwable t) {
            warnOnce("census parked virtual animals", t);
            return 0.0;
        }
    }

    private static String zoneAction(Object zoneObj) {
        try {
            Object action = zoneObj.getClass().getField("action").get(zoneObj);
            return action instanceof String s ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field zoneSpawnAnimalField(Class<?> cls) {
        Field f = fZoneSpawnAnimal;
        if (f == null) {
            fZoneSpawnAnimal = f = lookup(cls, "spawnAnimal");
        }
        return f;
    }

    private static Field zoneSpawnedAnimalsField(Class<?> cls) {
        Field f = fZoneSpawnedAnimals;
        if (f == null) {
            fZoneSpawnedAnimals = f = lookup(cls, "spawnedAnimals");
        }
        return f;
    }

    private static Field virtualAnimalsField(Class<?> cls) {
        Field f = fVirtualAnimals;
        if (f == null) {
            fVirtualAnimals = f = lookup(cls, "animals");
        }
        return f;
    }

    private static Field chunkAnimalsField(Class<?> cls) {
        Field f = fChunkAnimals;
        if (f == null) {
            fChunkAnimals = f = lookup(cls, "animals");
        }
        return f;
    }

    private static Field chunksWithTracksField() {
        Field f = fChunksWithTracks;
        if (f == null) {
            try {
                fChunksWithTracks =
                        f =
                                lookup(
                                        Class.forName("zombie.characters.animals.AnimalZones"),
                                        "chunksWithTracks");
            } catch (Throwable t) {
                warnOnce("resolve AnimalZones.chunksWithTracks", t);
                return null;
            }
        }
        return f;
    }

    private static Field lookup(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking the hierarchy
            } catch (Throwable t) {
                warnOnce("access " + cls.getName() + "." + name, t);
                return null;
            }
        }
        warnOnce("find field " + cls.getName() + "." + name, null);
        return null;
    }

    private static void warnOnce(String what, Throwable t) {
        if (!WARNED.add(what)) {
            return;
        }
        LOGGER.warn("AnimalSpawnMetrics failed to {} — that series will read 0", what, t);
    }
}
