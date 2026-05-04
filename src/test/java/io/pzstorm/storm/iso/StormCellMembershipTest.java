package io.pzstorm.storm.iso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import zombie.iso.IsoCell;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;

/**
 * Unit tests for {@link StormCellMembership}. {@link IsoCell} and {@link IsoObject} have
 * heavyweight constructors (cell allocation, ECS component registration), so fixtures are built via
 * {@link Unsafe#allocateInstance} — the sidecar only needs identity-keyed references, not
 * initialised game state.
 */
class StormCellMembershipTest implements UnitTest {

    private static final Unsafe UNSAFE = unsafe();

    private IsoCell cell;
    private IsoCell otherCell;

    @BeforeEach
    void setUp() throws Exception {
        cell = (IsoCell) UNSAFE.allocateInstance(IsoCell.class);
        otherCell = (IsoCell) UNSAFE.allocateInstance(IsoCell.class);
        StormCellMembership.resetForTesting(cell);
        StormCellMembership.resetForTesting(otherCell);
        // The constructors normally point IsoWorld.instance.currentCell at themselves; clear it
        // so the IsoWorld-driven fallback path tests can rely on a known starting state.
        if (IsoWorld.instance != null) {
            IsoWorld.instance.currentCell = null;
        }
    }

    // -------- staticUpdater: add / remove / index parity --------

    @Test
    void addToStaticUpdaterObjectListAppendsAndIndexes() throws Exception {
        IsoObject a = newObject();
        IsoObject b = newObject();
        ArrayList<IsoObject> list = new ArrayList<>();

        StormCellMembership.addToStaticUpdaterObjectList(cell, a, list);
        StormCellMembership.addToStaticUpdaterObjectList(cell, b, list);

        assertEquals(2, list.size());
        assertSame(a, list.get(0));
        assertSame(b, list.get(1));
        assertEquals(0, StormCellMembership.staticUpdaterIndexFor(cell, a));
        assertEquals(1, StormCellMembership.staticUpdaterIndexFor(cell, b));
    }

    @Test
    void addToStaticUpdaterObjectListIsIdempotent() throws Exception {
        IsoObject a = newObject();
        ArrayList<IsoObject> list = new ArrayList<>();

        StormCellMembership.addToStaticUpdaterObjectList(cell, a, list);
        StormCellMembership.addToStaticUpdaterObjectList(cell, a, list);

        assertEquals(1, list.size());
        assertEquals(0, StormCellMembership.staticUpdaterIndexFor(cell, a));
    }

    @Test
    void addToStaticUpdaterObjectListIgnoresNullObject() {
        ArrayList<IsoObject> list = new ArrayList<>();
        StormCellMembership.addToStaticUpdaterObjectList(cell, null, list);
        assertTrue(list.isEmpty());
    }

    @Test
    void removeStaticUpdaterDoesSwapWithLast() throws Exception {
        IsoObject a = newObject();
        IsoObject b = newObject();
        IsoObject c = newObject();
        ArrayList<IsoObject> list = new ArrayList<>();

        StormCellMembership.addToStaticUpdaterObjectList(cell, a, list);
        StormCellMembership.addToStaticUpdaterObjectList(cell, b, list);
        StormCellMembership.addToStaticUpdaterObjectList(cell, c, list);

        assertTrue(StormCellMembership.removeStaticUpdater(cell, a, list));

        // After swap-with-last: c moves into slot 0, b stays at slot 1, list shrinks to size 2.
        assertEquals(2, list.size());
        assertSame(c, list.get(0));
        assertSame(b, list.get(1));
        // Index of moved element c must be re-mapped to 0; a removed entirely.
        assertEquals(0, StormCellMembership.staticUpdaterIndexFor(cell, c));
        assertEquals(1, StormCellMembership.staticUpdaterIndexFor(cell, b));
        assertNull(StormCellMembership.staticUpdaterIndexFor(cell, a));
    }

    @Test
    void removeStaticUpdaterOfLastElementDoesNotSwap() throws Exception {
        IsoObject a = newObject();
        IsoObject b = newObject();
        ArrayList<IsoObject> list = new ArrayList<>();

        StormCellMembership.addToStaticUpdaterObjectList(cell, a, list);
        StormCellMembership.addToStaticUpdaterObjectList(cell, b, list);

        assertTrue(StormCellMembership.removeStaticUpdater(cell, b, list));

        assertEquals(1, list.size());
        assertSame(a, list.get(0));
        assertEquals(0, StormCellMembership.staticUpdaterIndexFor(cell, a));
        assertNull(StormCellMembership.staticUpdaterIndexFor(cell, b));
    }

    @Test
    void removeStaticUpdaterOfUnknownObjectReturnsFalse() throws Exception {
        IsoObject tracked = newObject();
        IsoObject orphan = newObject();
        ArrayList<IsoObject> list = new ArrayList<>();

        StormCellMembership.addToStaticUpdaterObjectList(cell, tracked, list);

        assertFalse(StormCellMembership.removeStaticUpdater(cell, orphan, list));
        // List untouched.
        assertEquals(1, list.size());
        assertSame(tracked, list.get(0));
    }

    @Test
    void removeStaticUpdaterRepeatedlyDrainsList() throws Exception {
        ArrayList<IsoObject> list = new ArrayList<>();
        IsoObject[] objects = new IsoObject[8];
        for (int i = 0; i < objects.length; i++) {
            objects[i] = newObject();
            StormCellMembership.addToStaticUpdaterObjectList(cell, objects[i], list);
        }

        // Remove from the middle each iteration; assert no orphan / duplicate / index drift.
        while (!list.isEmpty()) {
            IsoObject toRemove = list.get(list.size() / 2);
            assertTrue(StormCellMembership.removeStaticUpdater(cell, toRemove, list));
            // The remaining entries must each have an index pointing to themselves.
            for (int i = 0; i < list.size(); i++) {
                assertEquals(
                        i,
                        StormCellMembership.staticUpdaterIndexFor(cell, list.get(i)),
                        "index drift at slot " + i + ", remaining=" + list.size());
            }
        }
    }

    @Test
    void primeStaticUpdaterRebuildsIndex() throws Exception {
        IsoObject a = newObject();
        IsoObject b = newObject();
        ArrayList<IsoObject> list = new ArrayList<>();
        list.add(a);
        list.add(b);

        // Sidecar is empty — list was populated bypassing addToStaticUpdaterObjectList.
        assertNull(StormCellMembership.staticUpdaterIndexFor(cell, a));
        assertNull(StormCellMembership.staticUpdaterIndexFor(cell, b));

        StormCellMembership.primeStaticUpdater(cell, list);

        assertEquals(0, StormCellMembership.staticUpdaterIndexFor(cell, a));
        assertEquals(1, StormCellMembership.staticUpdaterIndexFor(cell, b));
        assertTrue(StormCellMembership.removeStaticUpdater(cell, a, list));
        assertEquals(1, list.size());
        assertSame(b, list.get(0));
    }

    // -------- processIsoObject set semantics --------

    @Test
    void addToProcessIsoObjectAppendsToProcessList() throws Exception {
        IsoObject a = newObject();
        ArrayList<IsoObject> processList = new ArrayList<>();
        ArrayList<IsoObject> removeList = new ArrayList<>();

        StormCellMembership.addToProcessIsoObject(cell, a, processList, removeList);

        assertEquals(1, processList.size());
        assertSame(a, processList.get(0));
        assertTrue(StormCellMembership.processIsoObjectContains(cell, a));
    }

    @Test
    void addToProcessIsoObjectIsIdempotent() throws Exception {
        IsoObject a = newObject();
        ArrayList<IsoObject> processList = new ArrayList<>();
        ArrayList<IsoObject> removeList = new ArrayList<>();

        StormCellMembership.addToProcessIsoObject(cell, a, processList, removeList);
        StormCellMembership.addToProcessIsoObject(cell, a, processList, removeList);

        assertEquals(1, processList.size());
    }

    @Test
    void addToProcessIsoObjectCancelsPendingRemove() throws Exception {
        IsoObject a = newObject();
        ArrayList<IsoObject> processList = new ArrayList<>();
        ArrayList<IsoObject> removeList = new ArrayList<>();

        StormCellMembership.addToProcessIsoObject(cell, a, processList, removeList);
        StormCellMembership.addToProcessIsoObjectRemove(cell, a, removeList);
        assertTrue(StormCellMembership.processIsoObjectPendingRemove(cell, a));
        assertEquals(1, removeList.size());

        // Re-adding while a pending tombstone exists must clear the tombstone (both sets and
        // both ArrayLists), matching vanilla's removeAll(...)+clear() flush semantics.
        StormCellMembership.addToProcessIsoObject(cell, a, processList, removeList);

        assertFalse(StormCellMembership.processIsoObjectPendingRemove(cell, a));
        assertTrue(removeList.isEmpty());
        // processList already contained `a` from the first add — no duplicate.
        assertEquals(1, processList.size());
    }

    @Test
    void addToProcessIsoObjectRemoveNoopsWhenObjectNotInProcessSet() throws Exception {
        IsoObject orphan = newObject();
        ArrayList<IsoObject> removeList = new ArrayList<>();

        StormCellMembership.addToProcessIsoObjectRemove(cell, orphan, removeList);

        assertTrue(removeList.isEmpty());
        assertFalse(StormCellMembership.processIsoObjectPendingRemove(cell, orphan));
    }

    @Test
    void addToProcessIsoObjectRemoveIsIdempotent() throws Exception {
        IsoObject a = newObject();
        ArrayList<IsoObject> processList = new ArrayList<>();
        ArrayList<IsoObject> removeList = new ArrayList<>();

        StormCellMembership.addToProcessIsoObject(cell, a, processList, removeList);
        StormCellMembership.addToProcessIsoObjectRemove(cell, a, removeList);
        StormCellMembership.addToProcessIsoObjectRemove(cell, a, removeList);

        assertEquals(1, removeList.size());
    }

    @Test
    void flushProcessIsoObjectRemovesClearsTombstones() throws Exception {
        IsoObject keep = newObject();
        IsoObject drop = newObject();
        ArrayList<IsoObject> processList = new ArrayList<>();
        ArrayList<IsoObject> removeList = new ArrayList<>();

        StormCellMembership.addToProcessIsoObject(cell, keep, processList, removeList);
        StormCellMembership.addToProcessIsoObject(cell, drop, processList, removeList);
        StormCellMembership.addToProcessIsoObjectRemove(cell, drop, removeList);

        StormCellMembership.flushProcessIsoObjectRemoves(cell);

        assertTrue(StormCellMembership.processIsoObjectContains(cell, keep));
        assertFalse(StormCellMembership.processIsoObjectContains(cell, drop));
        assertFalse(StormCellMembership.processIsoObjectPendingRemove(cell, drop));
    }

    // -------- per-cell isolation --------

    @Test
    void cellsAreIsolatedFromEachOther() throws Exception {
        IsoObject a = newObject();
        ArrayList<IsoObject> listA = new ArrayList<>();
        ArrayList<IsoObject> listB = new ArrayList<>();

        StormCellMembership.addToStaticUpdaterObjectList(cell, a, listA);

        assertEquals(0, StormCellMembership.staticUpdaterIndexFor(cell, a));
        assertNull(StormCellMembership.staticUpdaterIndexFor(otherCell, a));
        assertTrue(listB.isEmpty());
    }

    // -------- removeStaticUpdaterFromList substitution helper --------

    @Test
    void removeStaticUpdaterFromListFallsBackWhenNoCurrentCell() throws Exception {
        IsoObject a = newObject();
        IsoObject b = newObject();
        ArrayList<IsoObject> list = new ArrayList<>();
        list.add(a);
        list.add(b);
        IsoWorld.instance.currentCell = null;

        // No sidecar entry exists, currentCell is null — must fall through to ArrayList.remove.
        assertTrue(StormCellMembership.removeStaticUpdaterFromList(list, a));
        assertEquals(1, list.size());
        assertSame(b, list.get(0));
    }

    @Test
    void removeStaticUpdaterFromListUsesSidecarWhenCellPresent() throws Exception {
        IsoObject a = newObject();
        IsoObject b = newObject();
        ArrayList<IsoObject> list = new ArrayList<>();

        StormCellMembership.addToStaticUpdaterObjectList(cell, a, list);
        StormCellMembership.addToStaticUpdaterObjectList(cell, b, list);
        IsoWorld.instance.currentCell = cell;

        assertTrue(StormCellMembership.removeStaticUpdaterFromList(list, a));
        // Swap-with-last: b moves to slot 0; sidecar updated.
        assertEquals(1, list.size());
        assertSame(b, list.get(0));
        assertEquals(0, StormCellMembership.staticUpdaterIndexFor(cell, b));
    }

    @Test
    void removeStaticUpdaterFromListIgnoresNullArgs() throws Exception {
        ArrayList<IsoObject> list = new ArrayList<>();
        IsoObject a = newObject();
        list.add(a);

        assertFalse(StormCellMembership.removeStaticUpdaterFromList(null, a));
        assertFalse(StormCellMembership.removeStaticUpdaterFromList(list, null));
        // List untouched.
        assertEquals(1, list.size());
    }

    // -------- helpers --------

    private static IsoObject newObject() throws Exception {
        return (IsoObject) UNSAFE.allocateInstance(IsoObject.class);
    }

    private static Unsafe unsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Unsafe u = (Unsafe) f.get(null);
            assertNotNull(u);
            return u;
        } catch (Exception e) {
            throw new RuntimeException("Unable to acquire sun.misc.Unsafe", e);
        }
    }
}
