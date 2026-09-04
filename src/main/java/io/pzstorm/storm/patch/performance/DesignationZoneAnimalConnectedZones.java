package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.LongAdder;
import zombie.iso.areas.DesignationZoneAnimal;

/**
 * Replacement body for {@code DesignationZoneAnimal.getAllDZones(currentList, zone, previousZone)}
 * — the recursive flood-fill that collects every animal zone touching a given one (pens built as
 * several adjacent zones act as one).
 *
 * <p>Vanilla guards every perimeter probe with {@code result.contains(cZone)}, a linear scan of the
 * list it is building, so a connected group of {@code n} zones costs O(n × perimeter × n). The four
 * call sites of that scan were 51.4% of all {@code ArrayList.indexOfRange} on the ATF server
 * (2026-08-30 profile, ~1.0 ms of every tick): {@code IsoAnimal.update} re-runs the fill for every
 * animal on a 2000-tick timer, {@code check()} runs it per zone, and the trough / hutch / corpse
 * accessors run it on every query.
 *
 * <p>This walks the same tiles in the same order and adds to the same list, but answers "already
 * collected?" from a {@link HashSet} mirror instead of scanning. {@code DesignationZoneAnimal}
 * overrides neither {@code equals} nor {@code hashCode}, so the set's identity semantics match
 * {@code ArrayList.contains} exactly, and the output list is element-for-element identical to
 * vanilla's (verified against the vanilla method in {@code
 * DesignationZoneAnimalConnectedZonesParityTest}). In-place semantics are kept: a non-null {@code
 * currentList} is appended to and returned as the same object, which {@code IsoAnimal}'s {@code
 * connectedDZone} relies on.
 *
 * <p>The per-tile neighbour probe is the second cost. Vanilla's {@code
 * DesignationZoneAnimal.getZone(x, y, z)} is a linear scan of every zone on the server, and {@code
 * DesignationZone.update} re-runs {@code check()} — hence this fill — for every zone every 2.5 s:
 * 633 zones × 51,892 perimeter tiles = 32.8 M zone scans per pass, a ~60 ms hitch every fifth
 * second (scan #12, 2026-09-04). Probes here go through {@link #lookupZone}, a snapshot of the zone
 * list bucketed by 32×32 tile cells. The snapshot is re-validated against the live list on every
 * {@code getAllDZones} call (same zones, same order, same {@code x, y, z, w, h}) and rebuilt on any
 * difference, so it reflects exactly what vanilla's scan would see; bucket contents keep list
 * order, so an overlapping tile resolves to the same first-in-list zone. {@code
 * DesignationZoneAnimal.getZone} itself stays vanilla for its per-animal and per-object callers.
 *
 * <p>Fail-soft: any throw latches {@link #broken} and every later call falls through to the vanilla
 * body. A partially-filled {@code currentList} is safe to hand to vanilla because its own {@code
 * contains} guards skip what is already there.
 */
public final class DesignationZoneAnimalConnectedZones {

    /** Permanent fail-soft latch: any throw reverts to the vanilla flood-fill. */
    private static volatile boolean broken;

    private static final LongAdder OPTIMIZED_PASSES = new LongAdder();
    private static final LongAdder INDEX_REBUILDS = new LongAdder();

    private static volatile ZoneIndex index;

    private DesignationZoneAnimalConnectedZones() {}

    /**
     * Entry point for the advice. Returns the populated list, or {@code null} to let the vanilla
     * body run (latched off, or an internal failure on this call).
     */
    public static ArrayList<DesignationZoneAnimal> getAllDZones(
            ArrayList<DesignationZoneAnimal> currentList,
            DesignationZoneAnimal zone,
            DesignationZoneAnimal previousZone) {
        if (broken) {
            return null;
        }
        try {
            ArrayList<DesignationZoneAnimal> result =
                    currentList == null ? new ArrayList<>() : currentList;
            if (zone != null) {
                flood(currentIndex(), result, new HashSet<>(result), zone, previousZone);
            }
            OPTIMIZED_PASSES.increment();
            return result;
        } catch (Throwable t) {
            broken = true;
            LOGGER.error(
                    "Storm: DesignationZoneAnimal.getAllDZones fast path failed; reverting to"
                            + " vanilla",
                    t);
            return null;
        }
    }

    /**
     * Same answer as vanilla {@code DesignationZoneAnimal.getZone(x, y, z)} — the first zone in
     * {@code designationAnimalZoneList} order containing the tile — served from the validated
     * snapshot.
     */
    public static DesignationZoneAnimal lookupZone(int x, int y, int z) {
        return currentIndex().lookup(x, y, z);
    }

    private static ZoneIndex currentIndex() {
        ArrayList<DesignationZoneAnimal> live = DesignationZoneAnimal.designationAnimalZoneList;
        ZoneIndex current = index;
        if (current != null && current.matches(live)) {
            return current;
        }
        current = ZoneIndex.build(live);
        index = current;
        INDEX_REBUILDS.increment();
        return current;
    }

    private static void flood(
            ZoneIndex zones,
            ArrayList<DesignationZoneAnimal> result,
            HashSet<DesignationZoneAnimal> seen,
            DesignationZoneAnimal zone,
            DesignationZoneAnimal previousZone) {
        if (seen.add(zone)) {
            result.add(zone);
        }

        ArrayList<DesignationZoneAnimal> newConnected = new ArrayList<>();
        for (int x = zone.x; x < zone.x + zone.w; x++) {
            probe(result, seen, newConnected, previousZone, zones.lookup(x, zone.y - 1, zone.z));
            probe(
                    result,
                    seen,
                    newConnected,
                    previousZone,
                    zones.lookup(x, zone.y + zone.h, zone.z));
        }
        for (int y = zone.y; y < zone.y + zone.h; y++) {
            probe(result, seen, newConnected, previousZone, zones.lookup(zone.x - 1, y, zone.z));
            probe(
                    result,
                    seen,
                    newConnected,
                    previousZone,
                    zones.lookup(zone.x + zone.w, y, zone.z));
        }

        for (int i = 0; i < newConnected.size(); i++) {
            flood(zones, result, seen, newConnected.get(i), zone);
        }
    }

    private static void probe(
            ArrayList<DesignationZoneAnimal> result,
            HashSet<DesignationZoneAnimal> seen,
            ArrayList<DesignationZoneAnimal> newConnected,
            DesignationZoneAnimal previousZone,
            DesignationZoneAnimal candidate) {
        if (candidate != null && candidate != previousZone && seen.add(candidate)) {
            result.add(candidate);
            newConnected.add(candidate);
        }
    }

    /**
     * Immutable snapshot of the zone list: the zones and their geometry as captured, plus zones
     * grouped by every 32×32 tile bucket they overlap, each bucket in list order.
     */
    private static final class ZoneIndex {

        private static final int BUCKET_SHIFT = 5;
        private static final long COORD_MASK = (1L << 24) - 1;

        private final DesignationZoneAnimal[] zones;
        private final int[] geometry;
        private final HashMap<Long, DesignationZoneAnimal[]> buckets;

        private ZoneIndex(
                DesignationZoneAnimal[] zones,
                int[] geometry,
                HashMap<Long, DesignationZoneAnimal[]> buckets) {
            this.zones = zones;
            this.geometry = geometry;
            this.buckets = buckets;
        }

        static ZoneIndex build(ArrayList<DesignationZoneAnimal> live) {
            int n = live.size();
            DesignationZoneAnimal[] zones = new DesignationZoneAnimal[n];
            int[] geometry = new int[n * 5];
            HashMap<Long, ArrayList<DesignationZoneAnimal>> grouped = new HashMap<>();
            for (int i = 0; i < n; i++) {
                DesignationZoneAnimal zone = live.get(i);
                zones[i] = zone;
                int g = i * 5;
                geometry[g] = zone.x;
                geometry[g + 1] = zone.y;
                geometry[g + 2] = zone.z;
                geometry[g + 3] = zone.w;
                geometry[g + 4] = zone.h;
                if (zone.w <= 0 || zone.h <= 0) {
                    continue;
                }
                int bx0 = zone.x >> BUCKET_SHIFT;
                int bx1 = (zone.x + zone.w - 1) >> BUCKET_SHIFT;
                int by0 = zone.y >> BUCKET_SHIFT;
                int by1 = (zone.y + zone.h - 1) >> BUCKET_SHIFT;
                for (int bx = bx0; bx <= bx1; bx++) {
                    for (int by = by0; by <= by1; by++) {
                        grouped.computeIfAbsent(key(bx, by, zone.z), k -> new ArrayList<>())
                                .add(zone);
                    }
                }
            }
            HashMap<Long, DesignationZoneAnimal[]> buckets = new HashMap<>(grouped.size() * 2);
            grouped.forEach((k, v) -> buckets.put(k, v.toArray(new DesignationZoneAnimal[0])));
            return new ZoneIndex(zones, geometry, buckets);
        }

        boolean matches(ArrayList<DesignationZoneAnimal> live) {
            int n = zones.length;
            if (live.size() != n) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                DesignationZoneAnimal zone = live.get(i);
                int g = i * 5;
                if (zone != zones[i]
                        || zone.x != geometry[g]
                        || zone.y != geometry[g + 1]
                        || zone.z != geometry[g + 2]
                        || zone.w != geometry[g + 3]
                        || zone.h != geometry[g + 4]) {
                    return false;
                }
            }
            return true;
        }

        DesignationZoneAnimal lookup(int x, int y, int z) {
            DesignationZoneAnimal[] bucket =
                    buckets.get(key(x >> BUCKET_SHIFT, y >> BUCKET_SHIFT, z));
            if (bucket == null) {
                return null;
            }
            for (DesignationZoneAnimal zone : bucket) {
                if (x >= zone.x
                        && x < zone.x + zone.w
                        && y >= zone.y
                        && y < zone.y + zone.h
                        && zone.z == z) {
                    return zone;
                }
            }
            return null;
        }

        private static long key(int bx, int by, int z) {
            return ((long) z << 48) | ((bx & COORD_MASK) << 24) | (by & COORD_MASK);
        }
    }

    public static boolean isBroken() {
        return broken;
    }

    public static long getOptimizedPasses() {
        return OPTIMIZED_PASSES.sum();
    }

    public static long getIndexRebuilds() {
        return INDEX_REBUILDS.sum();
    }

    /** Test hook: clears the fail-soft latch and the zone snapshot. */
    public static void resetBroken() {
        broken = false;
        index = null;
    }
}
