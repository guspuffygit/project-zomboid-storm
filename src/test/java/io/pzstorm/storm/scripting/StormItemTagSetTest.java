package io.pzstorm.storm.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.entity.StormItemTagIndexHolder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The bit path must agree with identity {@link HashSet} membership under every mutator. */
class StormItemTagSetTest implements UnitTest {

    private static final class Tag implements StormItemTagIndexHolder {
        private final int index;

        Tag(int index) {
            this.index = index;
        }

        @Override
        public int getStormIndex() {
            return index;
        }
    }

    private static final Tag A = new Tag(0);
    private static final Tag B = new Tag(63);
    private static final Tag C = new Tag(64);
    private static final Tag D = new Tag(1000);
    private static final Object PLAIN = new Object();

    private static void assertAgrees(StormItemTagSet set, Set<Object> reference) {
        for (Object o : Arrays.asList(A, B, C, D, PLAIN, null, new Tag(5))) {
            assertEquals(reference.contains(o), set.contains(o), "contains " + o);
        }
        assertEquals(reference.size(), set.size());
    }

    @Test
    void addRemoveClearAgreeWithHashSet() {
        StormItemTagSet set = new StormItemTagSet();
        Set<Object> ref = new HashSet<>();
        for (Object o : Arrays.asList(A, B, C, D, PLAIN, null, A)) {
            assertEquals(ref.add(o), set.add(o));
        }
        assertAgrees(set, ref);
        assertEquals(ref.remove(B), set.remove(B));
        assertEquals(ref.remove(B), set.remove(B));
        assertEquals(ref.remove(PLAIN), set.remove(PLAIN));
        assertAgrees(set, ref);
        set.clear();
        ref.clear();
        assertAgrees(set, ref);
        assertFalse(set.contains(A));
    }

    @Test
    void bulkAndIteratorRemovalsRebuild() {
        StormItemTagSet set = new StormItemTagSet();
        Set<Object> ref = new HashSet<>();
        List<Object> all = Arrays.asList(A, B, C, D, PLAIN);
        set.addAll(all);
        ref.addAll(all);
        assertEquals(ref.removeAll(Arrays.asList(A, D)), set.removeAll(Arrays.asList(A, D)));
        assertAgrees(set, ref);
        assertEquals(
                ref.retainAll(Arrays.asList(B, PLAIN)), set.retainAll(Arrays.asList(B, PLAIN)));
        assertAgrees(set, ref);
        set.addAll(all);
        ref.addAll(all);
        assertEquals(ref.removeIf(o -> o == C), set.removeIf(o -> o == C));
        assertAgrees(set, ref);
        for (Iterator<Object> it = set.iterator(); it.hasNext(); ) {
            if (it.next() == B) {
                it.remove();
            }
        }
        ref.remove(B);
        assertAgrees(set, ref);
    }

    @Test
    void cloneHasIndependentMask() {
        StormItemTagSet set = new StormItemTagSet();
        set.add(A);
        StormItemTagSet copy = (StormItemTagSet) set.clone();
        copy.add(C);
        copy.remove(A);
        assertTrue(set.contains(A));
        assertFalse(set.contains(C));
        assertFalse(copy.contains(A));
        assertTrue(copy.contains(C));
    }

    @Test
    void maskGrowsPastSixtyFourWords() {
        long[] mask = new long[1];
        mask = StormTagMask.set(mask, 4097);
        assertTrue(StormTagMask.test(mask, 4097));
        assertFalse(StormTagMask.test(mask, 4096));
        assertFalse(StormTagMask.test(new long[1], 4097));
        StormTagMask.clear(mask, 4097);
        assertFalse(StormTagMask.test(mask, 4097));
        StormTagMask.clear(mask, 100000);
    }
}
