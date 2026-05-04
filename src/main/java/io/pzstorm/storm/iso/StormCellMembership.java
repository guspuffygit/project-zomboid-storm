package io.pzstorm.storm.iso;

import io.pzstorm.storm.metrics.CellObjectAddMetrics;
import io.pzstorm.storm.metrics.CellObjectRemoveMetrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.IsoCell;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;

/**
 * Per-{@link IsoCell} sidecar membership maps backing the O(1) replacements for {@code
 * IsoCell.addToProcessIsoObject}, {@code IsoCell.addToProcessIsoObjectRemove}, {@code
 * IsoCell.addToStaticUpdaterObjectList} and the {@code ArrayList.remove(Object)} call inside {@code
 * IsoObject.removeFromWorld()}.
 *
 * <p>The vanilla {@code IsoCell} stores those collections as plain {@code ArrayList}s, which forces
 * O(n) {@code .contains()} / {@code .remove(Object)} scans on every chunk unload. JFR analysis of
 * the live server (see {@code docs/recording2-jfr-analysis.md}) attributed the single largest CPU
 * bucket to {@code ArrayList.indexOfRange}, almost entirely from those scans.
 *
 * <p>Each sidecar entry is keyed weakly on the {@link IsoCell} so the sidecar is collected when the
 * cell is dropped (e.g. on world teardown) — there is no mod-side cleanup required when cells
 * unload. All mutations happen on the same thread that owns the matching {@link IsoCell} state in
 * vanilla; access is unsynchronised inside an {@code Entry} to match that contract. The cell lookup
 * table is guarded by a coarse lock to keep {@link WeakHashMap} from corrupting under accidental
 * cross-thread access.
 */
public final class StormCellMembership {

    private StormCellMembership() {}

    private static final class Entry {
        final Set<IsoObject> processIsoObjectSet =
                Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<IsoObject> processIsoObjectRemoveSet =
                Collections.newSetFromMap(new IdentityHashMap<>());
        final IdentityHashMap<IsoObject, Integer> staticUpdaterIndex = new IdentityHashMap<>();
    }

    private static final Map<IsoCell, Entry> CELL_TABLE = new WeakHashMap<>();

    private static final AtomicLong ADD_CALLS = new AtomicLong();
    private static final AtomicLong REMOVE_CALLS = new AtomicLong();

    private static Entry entryFor(IsoCell cell) {
        synchronized (CELL_TABLE) {
            Entry e = CELL_TABLE.get(cell);
            if (e == null) {
                e = new Entry();
                CELL_TABLE.put(cell, e);
            }
            return e;
        }
    }

    /**
     * Replacement body for {@code IsoCell.addToProcessIsoObject(IsoObject)}. Maintains the sidecar
     * set in lockstep with the underlying ArrayList so subsequent {@code .contains} checks become
     * O(1).
     */
    public static void addToProcessIsoObject(
            IsoCell cell,
            IsoObject object,
            ArrayList<IsoObject> processList,
            ArrayList<IsoObject> removeList) {
        if (object == null) {
            return;
        }
        long callIndex = ADD_CALLS.incrementAndGet();
        boolean sample = (callIndex & CellObjectAddMetrics.VANILLA_SAMPLE_MASK) == 0L;
        if (sample) {
            long sStart = System.nanoTime();
            removeList.indexOf(object);
            processList.indexOf(object);
            CellObjectAddMetrics.recordVanillaSimulatedNanos(System.nanoTime() - sStart);
        }
        long t0 = System.nanoTime();
        Entry e = entryFor(cell);
        if (e.processIsoObjectRemoveSet.remove(object)) {
            removeList.remove(object);
        }
        if (e.processIsoObjectSet.add(object)) {
            processList.add(object);
        }
        CellObjectAddMetrics.recordFastNanos(System.nanoTime() - t0);
    }

    /**
     * Replacement body for {@code IsoCell.addToProcessIsoObjectRemove(IsoObject)}. The vanilla
     * implementation does two ArrayList linear scans per call; here both are O(1).
     */
    public static void addToProcessIsoObjectRemove(
            IsoCell cell,
            IsoObject object,
            ArrayList<IsoObject> processList,
            ArrayList<IsoObject> removeList) {
        if (object == null) {
            return;
        }
        long callIndex = REMOVE_CALLS.incrementAndGet();
        boolean sample = (callIndex & CellObjectRemoveMetrics.VANILLA_SAMPLE_MASK) == 0L;
        if (sample) {
            long sStart = System.nanoTime();
            processList.indexOf(object);
            removeList.indexOf(object);
            CellObjectRemoveMetrics.recordVanillaSimulatedNanos(System.nanoTime() - sStart);
        }
        long t0 = System.nanoTime();
        Entry e = entryFor(cell);
        if (!e.processIsoObjectSet.contains(object)) {
            CellObjectRemoveMetrics.recordFastNanos(System.nanoTime() - t0);
            return;
        }
        if (!e.processIsoObjectRemoveSet.add(object)) {
            CellObjectRemoveMetrics.recordFastNanos(System.nanoTime() - t0);
            return;
        }
        removeList.add(object);
        CellObjectRemoveMetrics.recordFastNanos(System.nanoTime() - t0);
    }

    /**
     * Replacement body for {@code IsoCell.addToStaticUpdaterObjectList(IsoObject)}. Records the
     * index at which {@code object} was appended so the sidecar can drive an O(1) swap-with-last
     * removal on {@link #removeStaticUpdater}.
     */
    public static void addToStaticUpdaterObjectList(
            IsoCell cell, IsoObject object, ArrayList<IsoObject> list) {
        if (object == null) {
            return;
        }
        Entry e = entryFor(cell);
        if (e.staticUpdaterIndex.containsKey(object)) {
            return;
        }
        e.staticUpdaterIndex.put(object, list.size());
        list.add(object);
    }

    /**
     * Mirror the vanilla {@code processIsoObject.removeAll(processIsoObjectRemove)} + {@code
     * processIsoObjectRemove.clear()} flush onto the sidecar sets. Called from advice on {@code
     * IsoCell.ProcessIsoObject()} so the sidecar never accumulates stale tombstones.
     */
    public static void flushProcessIsoObjectRemoves(IsoCell cell) {
        Entry e = entryFor(cell);
        e.processIsoObjectSet.removeAll(e.processIsoObjectRemoveSet);
        e.processIsoObjectRemoveSet.clear();
    }

    /**
     * Performs the O(1) swap-with-last removal of {@code object} from {@code
     * staticUpdaterObjectList}, replacing the {@code ArrayList.remove(Object)} call originally
     * present in {@code IsoObject.removeFromWorld()}. Returns {@code true} if a removal occurred,
     * for parity with {@link ArrayList#remove(Object)}.
     *
     * <p>Order is not preserved — verified safe at all four call sites ({@code
     * IsoCell.ProcessStaticUpdaters}, {@code IsoCell:3500}, {@code FBORenderCell:292}, {@code
     * ServerGUI:299}, {@code TutorialManager:54}); none depend on insertion order.
     */
    public static boolean removeStaticUpdater(
            IsoCell cell, IsoObject object, ArrayList<IsoObject> list) {
        if (object == null) {
            return false;
        }
        Entry e = entryFor(cell);
        Integer idx = e.staticUpdaterIndex.remove(object);
        if (idx == null) {
            return false;
        }
        int i = idx;
        int last = list.size() - 1;
        if (i < 0 || i > last) {
            return false;
        }
        if (i != last) {
            IsoObject moved = list.get(last);
            list.set(i, moved);
            e.staticUpdaterIndex.put(moved, i);
        }
        list.remove(last);
        return true;
    }

    /**
     * Substitution target for the {@code ArrayList.remove(Object)} call inside {@code
     * IsoObject.removeFromWorld()}. Byte Buddy's {@code MemberSubstitution} replaces the original
     * {@code list.remove(this)} call with {@code removeStaticUpdaterFromList(list, this)} — same
     * receiver, same args, same boolean return type.
     *
     * <p>The cell is resolved via {@link IsoWorld#currentCell}, which is the singleton cell on both
     * client and server. If that field is null (during shutdown / world reload) we fall through to
     * the vanilla linear-scan remove so behaviour matches.
     */
    public static boolean removeStaticUpdaterFromList(ArrayList<IsoObject> list, Object obj) {
        if (list == null || obj == null) {
            return false;
        }
        IsoWorld world = IsoWorld.instance;
        IsoCell cell = world == null ? null : world.currentCell;
        if (cell == null) {
            return list.remove(obj);
        }
        return removeStaticUpdater(cell, (IsoObject) obj, list);
    }

    /** Test/debug only: clear the sidecar entry for {@code cell} (e.g. between unit-test cases). */
    public static void resetForTesting(IsoCell cell) {
        synchronized (CELL_TABLE) {
            CELL_TABLE.remove(cell);
        }
    }

    /**
     * Test/debug only: re-prime {@code staticUpdaterIndex} from the current ArrayList state. Use
     * this from a startup hook (or a periodic drift check) if there is any concern that vanilla
     * code populated the list without going through {@link #addToStaticUpdaterObjectList}.
     */
    public static void primeStaticUpdater(IsoCell cell, ArrayList<IsoObject> list) {
        Entry e = entryFor(cell);
        e.staticUpdaterIndex.clear();
        for (int i = 0; i < list.size(); i++) {
            IsoObject obj = list.get(i);
            if (obj != null) {
                e.staticUpdaterIndex.put(obj, i);
            }
        }
    }

    /**
     * Test/debug only: returns the sidecar's view of the static-updater index for the given object
     * (or {@code null} if untracked).
     */
    public static Integer staticUpdaterIndexFor(IsoCell cell, IsoObject object) {
        return entryFor(cell).staticUpdaterIndex.get(object);
    }

    /** Test/debug only: returns whether the sidecar has {@code object} in the process set. */
    public static boolean processIsoObjectContains(IsoCell cell, IsoObject object) {
        return entryFor(cell).processIsoObjectSet.contains(object);
    }

    /**
     * Test/debug only: returns whether the sidecar has {@code object} in the pending-remove set.
     */
    public static boolean processIsoObjectPendingRemove(IsoCell cell, IsoObject object) {
        return entryFor(cell).processIsoObjectRemoveSet.contains(object);
    }
}
