package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.UnitTest;
import java.util.HashSet;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link StormLongHashSet} answers exactly like {@code HashSet<Long>} across randomized
 * workloads, including the zero-key sentinel, growth across several resizes, and the packed (wx,wy)
 * coordinate keys the influence grid actually stores.
 */
class StormLongHashSetTest implements UnitTest {

    private static long coordKey(int wx, int wy) {
        return ((long) wx & 0xffffffffL) | (((long) wy & 0xffffffffL) << 32);
    }

    @Test
    void matchesReferenceSetUnderRandomizedWorkload() {
        Random random = new Random(42);
        for (int round = 0; round < 20; round++) {
            StormLongHashSet set = new StormLongHashSet();
            HashSet<Long> reference = new HashSet<>();
            for (int i = 0; i < 5000; i++) {
                // Small coordinate range forces collisions, duplicates and several growths.
                long key = coordKey(random.nextInt(200) - 100, random.nextInt(200) - 100);
                assertEquals(reference.add(key), set.add(key), "add " + key);
                assertEquals(reference.size(), set.size());
            }
            for (int wx = -110; wx <= 110; wx++) {
                for (int wy = -110; wy <= 110; wy++) {
                    long key = coordKey(wx, wy);
                    assertEquals(reference.contains(key), set.contains(key), "contains " + key);
                }
            }
            set.clear();
            assertEquals(0, set.size());
            for (Long key : reference) {
                assertFalse(set.contains(key));
            }
            // Reusable after clear (the grid clears and refills every tick).
            assertTrue(set.add(0L));
            assertTrue(set.contains(0L));
            assertFalse(set.add(0L));
            assertEquals(1, set.size());
        }
    }

    @Test
    void removeMatchesReferenceSetUnderRandomizedWorkload() {
        Random random = new Random(7);
        for (int round = 0; round < 20; round++) {
            StormLongHashSet set = new StormLongHashSet();
            HashSet<Long> reference = new HashSet<>();
            for (int i = 0; i < 20000; i++) {
                // A dense coordinate range keeps probe runs long, so removals land mid-run and
                // across the table's wrap, where clearing the slot alone would hide later keys.
                long key = coordKey(random.nextInt(60) - 30, random.nextInt(60) - 30);
                if (random.nextInt(3) == 0) {
                    assertEquals(reference.remove(key), set.remove(key), "remove " + key);
                } else {
                    assertEquals(reference.add(key), set.add(key), "add " + key);
                }
                assertEquals(reference.size(), set.size());
            }
            for (int wx = -32; wx <= 32; wx++) {
                for (int wy = -32; wy <= 32; wy++) {
                    long key = coordKey(wx, wy);
                    assertEquals(reference.contains(key), set.contains(key), "contains " + key);
                }
            }
            for (Long key : reference) {
                assertTrue(set.remove(key));
            }
            assertEquals(0, set.size());
        }
    }

    @Test
    void zeroKeyIsAValidMember() {
        StormLongHashSet set = new StormLongHashSet();
        assertFalse(set.contains(coordKey(0, 0)));
        assertTrue(set.add(coordKey(0, 0)));
        assertTrue(set.contains(coordKey(0, 0)));
        assertEquals(1, set.size());
        assertFalse(set.add(coordKey(0, 0)));
        set.clear();
        assertFalse(set.contains(coordKey(0, 0)));

        assertFalse(set.remove(0L));
        set.add(0L);
        set.add(coordKey(1, 0));
        assertTrue(set.remove(0L));
        assertFalse(set.contains(0L));
        assertTrue(set.contains(coordKey(1, 0)));
        assertEquals(1, set.size());
    }
}
