package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.ArrayList;
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
 * <p>{@code DesignationZoneAnimal.getZone(x, y, z)} — the per-tile neighbour lookup — is still
 * vanilla's linear scan over every zone on the server; it is not what profiled hot and is left
 * alone.
 *
 * <p>Fail-soft: any throw latches {@link #broken} and every later call falls through to the vanilla
 * body. A partially-filled {@code currentList} is safe to hand to vanilla because its own {@code
 * contains} guards skip what is already there.
 */
public final class DesignationZoneAnimalConnectedZones {

    /** Permanent fail-soft latch: any throw reverts to the vanilla flood-fill. */
    private static volatile boolean broken;

    private static final LongAdder OPTIMIZED_PASSES = new LongAdder();

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
                flood(result, new HashSet<>(result), zone, previousZone);
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

    private static void flood(
            ArrayList<DesignationZoneAnimal> result,
            HashSet<DesignationZoneAnimal> seen,
            DesignationZoneAnimal zone,
            DesignationZoneAnimal previousZone) {
        if (seen.add(zone)) {
            result.add(zone);
        }

        ArrayList<DesignationZoneAnimal> newConnected = new ArrayList<>();
        for (int x = zone.x; x < zone.x + zone.w; x++) {
            probe(
                    result,
                    seen,
                    newConnected,
                    previousZone,
                    DesignationZoneAnimal.getZone(x, zone.y - 1, zone.z));
            probe(
                    result,
                    seen,
                    newConnected,
                    previousZone,
                    DesignationZoneAnimal.getZone(x, zone.y + zone.h, zone.z));
        }
        for (int y = zone.y; y < zone.y + zone.h; y++) {
            probe(
                    result,
                    seen,
                    newConnected,
                    previousZone,
                    DesignationZoneAnimal.getZone(zone.x - 1, y, zone.z));
            probe(
                    result,
                    seen,
                    newConnected,
                    previousZone,
                    DesignationZoneAnimal.getZone(zone.x + zone.w, y, zone.z));
        }

        for (int i = 0; i < newConnected.size(); i++) {
            flood(result, seen, newConnected.get(i), zone);
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

    public static boolean isBroken() {
        return broken;
    }

    public static long getOptimizedPasses() {
        return OPTIMIZED_PASSES.sum();
    }

    /** Test hook: clears the fail-soft latch. */
    public static void resetBroken() {
        broken = false;
    }
}
