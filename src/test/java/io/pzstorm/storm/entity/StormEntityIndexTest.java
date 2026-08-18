package io.pzstorm.storm.entity;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@link StormEntityIndex} helpers exactly as the woven advices would call them: an
 * add helper mirrors {@code EntityArrayAddAdvice} (vanilla append, then {@code onArrayAdd}) and a
 * remove helper mirrors {@code EntityArrayRemoveValueAdvice} (consult {@code onRemoveValue}, fall
 * through to the vanilla body on verdict 0). {@link TestArray} stands in for the woven {@code
 * zombie.entity.util.Array} — a real game {@code Array} subclass carrying the injected index slot.
 */
class StormEntityIndexTest implements UnitTest {

    /** Equivalent of the woven Array: real game class + the injected interface/field. */
    static class TestArray extends zombie.entity.util.Array<Object> implements StormIndexedArray {

        volatile Object stormEntityArrayIndex;

        TestArray(boolean ordered) {
            super(ordered, 16);
        }

        @Override
        public Object getStormEntityArrayIndex() {
            return stormEntityArrayIndex;
        }

        @Override
        public void setStormEntityArrayIndex(Object index) {
            stormEntityArrayIndex = index;
        }
    }

    /** Distinct-identity element whose equals matches other elements with the same key. */
    static final class Entity {

        final int key;

        Entity(int key) {
            this.key = key;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Entity e && e.key == key;
        }

        @Override
        public int hashCode() {
            return key;
        }

        @Override
        public String toString() {
            return "Entity(" + key + ")";
        }
    }

    @BeforeEach
    void reset() {
        StormEntityIndex.resetForTesting();
    }

    private static void wovenAdd(TestArray array, Object value) {
        array.add(value);
        StormEntityIndex.onArrayAdd(array, value);
    }

    private static boolean wovenRemove(TestArray array, Object value, boolean identity) {
        int verdict = StormEntityIndex.onRemoveValue(array, value, identity);
        if (verdict != 0) {
            return verdict == 1;
        }
        return array.removeValue(value, identity);
    }

    private static int verdict(TestArray array, Object value, boolean identity) {
        return StormEntityIndex.onRemoveValue(array, value, identity);
    }

    /** Asserts the attached index maps every element to its actual position, and nothing else. */
    private static void assertIndexConsistent(TestArray array) {
        EntityArrayIndex idx = (EntityArrayIndex) array.getStormEntityArrayIndex();
        Assertions.assertNotNull(idx);
        Assertions.assertEquals(array.size, idx.map.size());
        for (int i = 0; i < array.size; i++) {
            Assertions.assertEquals(i, idx.map.get(array.items[i]));
        }
    }

    @Test
    void addAndRemoveMaintainIndexAndContents() {
        TestArray array = new TestArray(false);
        Assertions.assertNotNull(StormEntityIndex.registerArray(array, "test"));

        List<Entity> model = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Entity e = new Entity(i);
            model.add(e);
            wovenAdd(array, e);
        }
        assertIndexConsistent(array);

        for (Entity e : model) {
            Assertions.assertTrue(wovenRemove(array, e, true));
        }
        Assertions.assertEquals(0, array.size);
        assertIndexConsistent(array);
    }

    @Test
    void removalUsesFastVerdictAndFixesUpSwappedElement() {
        TestArray array = new TestArray(false);
        StormEntityIndex.registerArray(array, "test");
        Entity a = new Entity(1);
        Entity b = new Entity(2);
        Entity c = new Entity(3);
        wovenAdd(array, a);
        wovenAdd(array, b);
        wovenAdd(array, c);

        Assertions.assertEquals(1, verdict(array, a, true));
        // Unordered removeIndex swaps the last element into the hole; the fixup must re-point it.
        Assertions.assertSame(c, array.items[0]);
        Assertions.assertSame(b, array.items[1]);
        assertIndexConsistent(array);

        Assertions.assertEquals(1, verdict(array, c, true));
        Assertions.assertSame(b, array.items[0]);
        assertIndexConsistent(array);
    }

    @Test
    void missingValueReturnsFalseVerdict() {
        TestArray array = new TestArray(false);
        StormEntityIndex.registerArray(array, "test");
        wovenAdd(array, new Entity(1));

        Assertions.assertFalse(wovenRemove(array, new Entity(99), true));
        Assertions.assertEquals(1, array.size);
        Assertions.assertFalse(wovenRemove(array, null, true));
        assertIndexConsistent(array);
    }

    @Test
    void equalsBasedRemovalScansButKeepsIndexConsistent() {
        TestArray array = new TestArray(false);
        StormEntityIndex.registerArray(array, "test");
        Entity a = new Entity(1);
        Entity b = new Entity(2);
        wovenAdd(array, a);
        wovenAdd(array, b);

        // Equal-but-not-same value: the identity index cannot answer; the inline scan must
        // remove the stored instance and keep the index in sync.
        Assertions.assertTrue(wovenRemove(array, new Entity(1), false));
        Assertions.assertEquals(1, array.size);
        Assertions.assertSame(b, array.items[0]);
        assertIndexConsistent(array);
    }

    @Test
    void untrackedAndUnwovenArraysFallThroughToVanilla() {
        TestArray untracked = new TestArray(false);
        Entity a = new Entity(1);
        untracked.add(a);
        Assertions.assertEquals(0, verdict(untracked, a, true));
        Assertions.assertTrue(wovenRemove(untracked, a, true));

        zombie.entity.util.Array<Object> plain = new zombie.entity.util.Array<>(false, 4);
        Assertions.assertNull(StormEntityIndex.registerArray(plain, "plain"));
        Assertions.assertEquals(0, StormEntityIndex.onRemoveValue(plain, a, true));
    }

    @Test
    void orderedArrayIsRefused() {
        TestArray ordered = new TestArray(true);
        Assertions.assertNull(StormEntityIndex.registerArray(ordered, "ordered"));
        Entity a = new Entity(1);
        ordered.add(a);
        Assertions.assertEquals(0, verdict(ordered, a, true));
    }

    @Test
    void killSwitchOffGoesVanillaAndReEnableRebuilds() {
        TestArray array = new TestArray(false);
        StormEntityIndex.registerArray(array, "test");
        Entity a = new Entity(1);
        Entity b = new Entity(2);
        wovenAdd(array, a);
        wovenAdd(array, b);

        StormEntityIndex.setEnabled(false);
        // Vanilla path while off: the index silently goes stale.
        Assertions.assertEquals(0, verdict(array, b, true));
        Assertions.assertTrue(array.removeValue(b, true));
        Entity c = new Entity(3);
        wovenAdd(array, c);

        StormEntityIndex.setEnabled(true);
        // First touch after re-enable rebuilds from the array, so both survivors resolve fast.
        Assertions.assertTrue(wovenRemove(array, c, true));
        Assertions.assertTrue(wovenRemove(array, a, true));
        Assertions.assertEquals(0, array.size);
        assertIndexConsistent(array);
    }

    @Test
    void selfCheckMismatchLatchesVanillaPermanently() {
        TestArray array = new TestArray(false);
        EntityArrayIndex idx = StormEntityIndex.registerArray(array, "test");
        Entity a = new Entity(1);
        Entity b = new Entity(2);
        wovenAdd(array, a);
        wovenAdd(array, b);

        // Simulated desync bug: index claims b sits where a actually is.
        idx.map.put(b, 0);
        Assertions.assertEquals(0, verdict(array, b, true));
        Assertions.assertTrue(idx.map.isEmpty(), "latched index must be cleared");

        // Latched: every tracked array reverts to vanilla, which still removes correctly.
        TestArray other = new TestArray(false);
        StormEntityIndex.registerArray(other, "other");
        Entity x = new Entity(9);
        wovenAdd(other, x);
        Assertions.assertEquals(0, verdict(other, x, true));
        Assertions.assertTrue(wovenRemove(other, x, true));
        Assertions.assertTrue(wovenRemove(array, b, true));

        // Re-enabling does not clear the failure latch.
        StormEntityIndex.setEnabled(false);
        StormEntityIndex.setEnabled(true);
        Assertions.assertEquals(0, verdict(array, a, true));
    }

    @Test
    void independentArraysTrackTheSameElementSeparately() {
        TestArray first = new TestArray(false);
        TestArray second = new TestArray(false);
        StormEntityIndex.registerArray(first, "first");
        StormEntityIndex.registerArray(second, "second");
        Entity shared = new Entity(1);
        wovenAdd(first, shared);
        wovenAdd(second, new Entity(2));
        wovenAdd(second, shared);

        Assertions.assertTrue(wovenRemove(first, shared, true));
        Assertions.assertEquals(0, first.size);
        Assertions.assertEquals(2, second.size);
        assertIndexConsistent(second);
        Assertions.assertTrue(wovenRemove(second, shared, true));
        assertIndexConsistent(second);
    }

    @Test
    void registerIsIdempotentAndPreservesExistingIndex() {
        TestArray array = new TestArray(false);
        EntityArrayIndex idx = StormEntityIndex.registerArray(array, "test");
        wovenAdd(array, new Entity(1));
        Assertions.assertSame(idx, StormEntityIndex.registerArray(array, "test"));
        assertIndexConsistent(array);
    }

    @Test
    void registrationOfPopulatedArrayIndexesExistingContents() {
        TestArray array = new TestArray(false);
        Entity a = new Entity(1);
        Entity b = new Entity(2);
        array.add(a);
        array.add(b);
        StormEntityIndex.registerArray(array, "test");
        assertIndexConsistent(array);
        Assertions.assertEquals(1, verdict(array, a, true));
        Assertions.assertEquals(1, verdict(array, b, true));
        Assertions.assertEquals(0, array.size);
    }

    @Test
    void fuzzAgainstModelWithTogglesAndMixedModes() {
        Random rng = new Random(20260818);
        TestArray array = new TestArray(false);
        StormEntityIndex.registerArray(array, "fuzz");
        List<Entity> model = new ArrayList<>();
        boolean on = true;

        for (int op = 0; op < 20_000; op++) {
            int roll = rng.nextInt(100);
            if (roll < 45 || model.isEmpty()) {
                // Unique key per element so equals-mode and identity-mode removals target the
                // same instance and the model stays instance-synchronized with the array.
                Entity e = new Entity(op);
                model.add(e);
                wovenAdd(array, e);
            } else if (roll < 90) {
                // identity=false still diverges internally: it exercises the inline scan +
                // fixup path instead of the map lookup.
                boolean identity = rng.nextBoolean();
                Entity victim = model.remove(rng.nextInt(model.size()));
                Assertions.assertTrue(
                        wovenRemove(array, victim, identity), "victim was present at op " + op);
            } else if (roll < 95) {
                Entity absent = new Entity(-1 - rng.nextInt(500));
                Assertions.assertFalse(wovenRemove(array, absent, rng.nextBoolean()));
            } else {
                on = !on;
                StormEntityIndex.setEnabled(on);
            }

            if (op % 500 == 0) {
                Assertions.assertEquals(model.size(), array.size, "size diverged at op " + op);
            }
        }

        StormEntityIndex.setEnabled(true);
        Assertions.assertEquals(model.size(), array.size);
        for (Entity e : model) {
            Assertions.assertTrue(wovenRemove(array, e, true), "missing " + e);
        }
        Assertions.assertEquals(0, array.size);
        assertIndexConsistent(array);
    }
}
