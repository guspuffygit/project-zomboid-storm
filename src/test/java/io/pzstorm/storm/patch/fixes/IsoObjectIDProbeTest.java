package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the {@link IsoObjectIDProbe#nextFreeId(short, Map)} algorithm in isolation from the
 * ByteBuddy plumbing. The probe is what makes {@code IsoObjectID.allocateID()} collision-safe; if
 * it ever returns an ID that is already a key in the supplied map (other than {@link
 * IsoObjectIDProbe#ID_INVALID}) the mitosis bug returns.
 *
 * <p>Concerns covered:
 *
 * <ul>
 *   <li>Empty map — advances by one and returns immediately, never touching {@code startNextId}.
 *   <li>Skipping a single occupied slot.
 *   <li>Skipping the {@code -1} sentinel when wrapping past {@code Short.MAX_VALUE}.
 *   <li>Wrap-around the full 16-bit short space.
 *   <li>Fully-saturated pool — must return {@link IsoObjectIDProbe#ID_INVALID} after exactly one
 *       sweep, not loop forever.
 *   <li>A single free slot in an otherwise full pool — must locate it regardless of starting
 *       position.
 * </ul>
 */
class IsoObjectIDProbeTest implements UnitTest {

    @Test
    void emptyMapReturnsCursorPlusOne() {
        Map<Short, Object> empty = new HashMap<>();
        assertEquals((short) 1, IsoObjectIDProbe.nextFreeId((short) 0, empty));
        assertEquals((short) 101, IsoObjectIDProbe.nextFreeId((short) 100, empty));
        assertEquals(Short.MIN_VALUE, IsoObjectIDProbe.nextFreeId(Short.MAX_VALUE, empty));
    }

    @Test
    void singleOccupiedSlotIsSkipped() {
        Map<Short, Object> map = new HashMap<>();
        map.put((short) 5, new Object());
        // startNextId=4 → tries 5 (occupied) → returns 6
        assertEquals((short) 6, IsoObjectIDProbe.nextFreeId((short) 4, map));
    }

    @Test
    void runOfOccupiedSlotsIsSkipped() {
        Map<Short, Object> map = new HashMap<>();
        for (short id = 10; id <= 20; id++) {
            map.put(id, new Object());
        }
        assertEquals((short) 21, IsoObjectIDProbe.nextFreeId((short) 9, map));
    }

    @Test
    void invalidSentinelIsSkippedOnWrap() {
        Map<Short, Object> empty = new HashMap<>();
        // startNextId=Short.MAX_VALUE → id++ wraps to Short.MIN_VALUE (-32768), keeps walking up
        // through -1 → -1 is sentinel → must be skipped → returns 0.
        // From -2 we should get 0 directly: -2 → -1 (sentinel, skip) → 0.
        assertEquals((short) 0, IsoObjectIDProbe.nextFreeId((short) -2, empty));
        // Confirm probe never hands out -1 even if the cursor is sitting on -2 and the map is
        // empty.
        assertNotEquals(
                IsoObjectIDProbe.ID_INVALID, IsoObjectIDProbe.nextFreeId((short) -2, empty));
    }

    @Test
    void exhaustedPoolReturnsInvalid() {
        Map<Short, Object> full = new HashMap<>();
        Object filler = new Object();
        for (int i = Short.MIN_VALUE; i <= Short.MAX_VALUE; i++) {
            if (i == IsoObjectIDProbe.ID_INVALID) {
                continue;
            }
            full.put((short) i, filler);
        }
        assertEquals(IsoObjectIDProbe.VALID_ID_COUNT, full.size());
        assertEquals(IsoObjectIDProbe.ID_INVALID, IsoObjectIDProbe.nextFreeId((short) 0, full));
        assertEquals(
                IsoObjectIDProbe.ID_INVALID, IsoObjectIDProbe.nextFreeId(Short.MAX_VALUE, full));
    }

    @Test
    void singleFreeSlotIsFoundFromAnyStartingPosition() {
        // Drain every slot except 12345.
        Map<Short, Object> map = new HashMap<>();
        Object filler = new Object();
        for (int i = Short.MIN_VALUE; i <= Short.MAX_VALUE; i++) {
            if (i == IsoObjectIDProbe.ID_INVALID || i == 12345) {
                continue;
            }
            map.put((short) i, filler);
        }

        // From any starting cursor the probe must locate 12345 within VALID_ID_COUNT steps.
        for (int start : new int[] {0, 12344, 12345, 12346, Short.MAX_VALUE, Short.MIN_VALUE}) {
            assertEquals(
                    (short) 12345,
                    IsoObjectIDProbe.nextFreeId((short) start, map),
                    "Should find the single free slot starting from " + start);
        }
    }

    @Test
    void neverReturnsAnOccupiedIdOverManyAllocations() {
        // Realistic shape: ~half of the slots are in use, simulate the allocate+put cycle the
        // patched server runs. Every allocation must hand out a fresh ID.
        Map<Short, Object> map = new HashMap<>();
        short cursor = 0;

        // Seed the map so the address space is non-trivially fragmented.
        Object filler = new Object();
        for (int i = 0; i < 30000; i++) {
            map.put((short) (i * 2), filler);
        }
        int seeded = map.size();

        Set<Short> handedOut = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            short id = IsoObjectIDProbe.nextFreeId(cursor, map);
            assertNotEquals(
                    IsoObjectIDProbe.ID_INVALID,
                    id,
                    "Pool should not exhaust: only " + map.size() + " slots used");
            assertTrue(handedOut.add(id), "Probe handed out a duplicate ID: " + id);
            map.put(id, filler);
            cursor = id;
        }

        // Map must have grown by exactly the allocation count — no collisions overwrote a seed.
        assertEquals(seeded + 5000, map.size());
    }

    @Test
    void cursorIsAdvancedExactlyOnEachCallEvenWhenSlotJustVacated() {
        // Mirrors the failure-recovery story: pool was exhausted, one slot has just been freed,
        // the next allocateID() call must hand that slot out without restarting from zero.
        Map<Short, Object> full = new HashMap<>();
        Object filler = new Object();
        for (int i = Short.MIN_VALUE; i <= Short.MAX_VALUE; i++) {
            if (i == IsoObjectIDProbe.ID_INVALID) {
                continue;
            }
            full.put((short) i, filler);
        }

        // Exhausted — should return -1 from any cursor position.
        assertEquals(IsoObjectIDProbe.ID_INVALID, IsoObjectIDProbe.nextFreeId((short) 1234, full));

        // Free one slot and retry — the probe should find it within one full sweep.
        full.remove((short) 9000);
        assertEquals((short) 9000, IsoObjectIDProbe.nextFreeId((short) 1234, full));
    }
}
