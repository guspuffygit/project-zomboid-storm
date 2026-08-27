package io.pzstorm.storm.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Oracle test: every observable behavior of {@link StormFastContainsList} must match a plain {@code
 * ArrayList} fed the same operations — including the JDK paths that re-enter mutation virtually
 * (iterator removal, {@code ListIterator.set/add}, {@code subList().clear()}, the {@code
 * SequencedCollection} defaults) — while the count-map mirror keeps {@code contains}/{@code
 * indexOf}/{@code lastIndexOf} answers exact. Plus the one deliberate behavior change: {@code
 * removeAll(empty)} returns without probing the argument at all.
 */
class StormFastContainsListTest implements UnitTest {

    /** Small value universe with guaranteed duplicates and a null. */
    private static final String[] UNIVERSE = {
        "a", "b", "c", "d", "e", "f", "g", "h", null, "never-added"
    };

    @Test
    void fuzzAgainstArrayListOracle() {
        Random random = new Random(42);
        for (int round = 0; round < 50; round++) {
            StormFastContainsList<String> fast = new StormFastContainsList<>();
            ArrayList<String> oracle = new ArrayList<>();
            for (int op = 0; op < 400; op++) {
                mutateBoth(random, fast, oracle);
                if (op % 25 == 0) {
                    assertAgreement(fast, oracle);
                }
            }
            assertAgreement(fast, oracle);
        }
    }

    private static void mutateBoth(
            Random random, StormFastContainsList<String> fast, ArrayList<String> oracle) {
        String value = UNIVERSE[random.nextInt(UNIVERSE.length - 1)];
        int size = oracle.size();
        switch (random.nextInt(15)) {
            case 0 -> {
                fast.add(value);
                oracle.add(value);
            }
            case 1 -> {
                int index = random.nextInt(size + 1);
                fast.add(index, value);
                oracle.add(index, value);
            }
            case 2 -> assertEquals(oracle.remove(value), fast.remove(value));
            case 3 -> {
                if (size > 0) {
                    int index = random.nextInt(size);
                    assertEquals(oracle.remove(index), fast.remove(index));
                }
            }
            case 4 -> {
                if (size > 0) {
                    int index = random.nextInt(size);
                    assertEquals(oracle.set(index, value), fast.set(index, value));
                }
            }
            case 5 -> {
                List<String> batch = randomBatch(random);
                assertEquals(oracle.addAll(batch), fast.addAll(batch));
            }
            case 6 -> {
                List<String> batch = randomBatch(random);
                int index = random.nextInt(size + 1);
                assertEquals(oracle.addAll(index, batch), fast.addAll(index, batch));
            }
            case 7 -> {
                List<String> batch = randomBatch(random);
                assertEquals(oracle.removeAll(batch), fast.removeAll(batch));
            }
            case 8 -> {
                List<String> batch = randomBatch(random);
                if (random.nextBoolean()) {
                    assertEquals(oracle.retainAll(batch), fast.retainAll(batch));
                }
            }
            case 9 -> {
                String pivot = value == null ? "d" : value;
                assertEquals(
                        oracle.removeIf(s -> s != null && s.compareTo(pivot) < 0),
                        fast.removeIf(s -> s != null && s.compareTo(pivot) < 0));
            }
            case 10 -> {
                fast.replaceAll(s -> s == null ? null : s.toUpperCase().toLowerCase());
                oracle.replaceAll(s -> s == null ? null : s.toUpperCase().toLowerCase());
            }
            case 11 -> {
                if (size > 1) {
                    int from = random.nextInt(size - 1);
                    int to = from + 1 + random.nextInt(size - from - 1);
                    fast.subList(from, to).clear();
                    oracle.subList(from, to).clear();
                }
            }
            case 12 -> {
                Iterator<String> fastIt = fast.iterator();
                Iterator<String> oracleIt = oracle.iterator();
                while (fastIt.hasNext() && oracleIt.hasNext()) {
                    String a = fastIt.next();
                    assertEquals(oracleIt.next(), a);
                    if (a != null && a.equals(value)) {
                        fastIt.remove();
                        oracleIt.remove();
                    }
                }
            }
            case 13 -> {
                if (size > 0) {
                    ListIterator<String> fastIt = fast.listIterator();
                    ListIterator<String> oracleIt = oracle.listIterator();
                    fastIt.next();
                    oracleIt.next();
                    if (random.nextBoolean()) {
                        fastIt.set(value);
                        oracleIt.set(value);
                    } else {
                        fastIt.add(value);
                        oracleIt.add(value);
                    }
                }
            }
            case 14 -> {
                if (random.nextInt(10) == 0) {
                    fast.clear();
                    oracle.clear();
                }
            }
            default -> throw new AssertionError();
        }
    }

    private static List<String> randomBatch(Random random) {
        int n = random.nextInt(4);
        List<String> batch = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            batch.add(UNIVERSE[random.nextInt(UNIVERSE.length)]);
        }
        return batch;
    }

    private static void assertAgreement(
            StormFastContainsList<String> fast, ArrayList<String> oracle) {
        assertEquals(oracle, fast, "list contents diverged from the ArrayList oracle");
        for (String probe : UNIVERSE) {
            assertEquals(oracle.contains(probe), fast.contains(probe), "contains(" + probe + ")");
            assertEquals(oracle.indexOf(probe), fast.indexOf(probe), "indexOf(" + probe + ")");
            assertEquals(
                    oracle.lastIndexOf(probe),
                    fast.lastIndexOf(probe),
                    "lastIndexOf(" + probe + ")");
        }
    }

    @Test
    void sequencedCollectionDefaultsStayMirrored() {
        StormFastContainsList<String> fast = new StormFastContainsList<>();
        fast.addFirst("b");
        fast.addFirst("a");
        fast.addLast("c");
        assertEquals(List.of("a", "b", "c"), fast);
        assertTrue(fast.contains("a") && fast.contains("b") && fast.contains("c"));
        assertEquals("a", fast.removeFirst());
        assertEquals("c", fast.removeLast());
        assertFalse(fast.contains("a"));
        assertFalse(fast.contains("c"));
        assertTrue(fast.contains("b"));
    }

    @Test
    void removeAllEmptyShortCircuitsWithoutProbingTheArgument() {
        StormFastContainsList<String> fast = new StormFastContainsList<>();
        fast.addAll(Arrays.asList("a", "b", "c"));
        ContainsCountingList empty = new ContainsCountingList();
        assertFalse(fast.removeAll(empty));
        assertEquals(0, empty.containsCalls, "an empty removeAll argument must never be probed");
        assertEquals(List.of("a", "b", "c"), fast);

        // Non-empty arguments still go through the vanilla element-by-element filter.
        ContainsCountingList nonEmpty = new ContainsCountingList();
        nonEmpty.add("b");
        assertTrue(fast.removeAll(nonEmpty));
        assertTrue(nonEmpty.containsCalls > 0);
        assertEquals(List.of("a", "c"), fast);
        assertFalse(fast.contains("b"));
    }

    @Test
    void copyOfPreservesElementsAndNonCollectionYieldsEmpty() {
        ArrayList<String> source = new ArrayList<>(Arrays.asList("x", "y", "x", null));
        StormFastContainsList<String> copy = StormFastContainsList.copyOf(source);
        assertEquals(source, copy);
        assertTrue(copy.contains("x") && copy.contains(null));
        assertEquals(2, copy.lastIndexOf("x"));

        StormFastContainsList<String> fromNull = StormFastContainsList.copyOf(null);
        assertTrue(fromNull.isEmpty());
        StormFastContainsList<String> fromNonCollection =
                StormFastContainsList.copyOf("not a list");
        assertTrue(fromNonCollection.isEmpty());
    }

    @Test
    void cloneIsIndependentWithItsOwnMirror() {
        StormFastContainsList<String> original = new StormFastContainsList<>();
        original.addAll(Arrays.asList("a", "b"));
        @SuppressWarnings("unchecked")
        StormFastContainsList<String> clone = (StormFastContainsList<String>) original.clone();
        assertNotSame(original, clone);
        assertEquals(original, clone);
        clone.remove("a");
        assertTrue(original.contains("a"), "mutating the clone must not desync the original");
        assertFalse(clone.contains("a"));
    }

    /** Minimal collection that counts {@code contains} probes. */
    private static final class ContainsCountingList extends ArrayList<String> {
        int containsCalls;

        @Override
        public boolean contains(Object o) {
            containsCalls++;
            return super.contains(o);
        }
    }
}
