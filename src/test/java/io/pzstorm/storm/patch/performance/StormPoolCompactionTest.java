package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Reproduces the vanilla {@code Pool.PoolStacks} probe-length cliff on a real Trove {@code
 * THashSet} configured exactly the way {@code zombie.util.Pool} configures it, and proves that
 * {@code compact()} — the call {@link StormPoolCompaction} makes — unwinds it without losing
 * elements.
 *
 * <p>The set is driven through the same shape as a pool under churn: fill, then alternate
 * remove/add so that live size stays flat while total inserts climb. That drains {@code _free}
 * (each insert that reaches a FREE slot consumes one) without ever letting {@code _size} exceed
 * {@code _maxSize}, so neither vanilla rehash trigger can fire and the table degrades permanently.
 *
 * <p>Trove is reached reflectively for the same reason the production sweep does it: the concrete
 * field layout lives on Trove base classes and nothing in Storm should compile against them.
 */
class StormPoolCompactionTest implements UnitTest {

    private static final String THASHSET = "gnu.trove.set.hash.THashSet";

    private static final int LIVE_SIZE = 1000;

    private static final int CHURN_INSERTS = 4000;

    /**
     * The FREE-slot drain is asymptotic — an insert only consumes a FREE slot when its probe met no
     * tombstone on the way — so the number of inserts needed to reach the cliff is not a fixed
     * count. This bounds the churn loop well above the ~9k the shape predicts.
     */
    private static final int CHURN_LIMIT = 500_000;

    @Test
    void probeEstimateTracksFreeSlotCount() {
        assertEquals(1, StormPoolCompaction.probeEstimate(2048, 2047));
        assertEquals(2, StormPoolCompaction.probeEstimate(2048, 1023));
        assertEquals(39196, StormPoolCompaction.probeEstimate(823117, 20));
        assertEquals(0, StormPoolCompaction.probeEstimate(0, 0));
        assertEquals(0, StormPoolCompaction.probeEstimate(-1, 0));
    }

    @Test
    void shouldCompactOnlyWhenBigAndDegenerate() {
        assertTrue(StormPoolCompaction.shouldCompact(823117, 20, 64, 1024));

        assertFalse(
                StormPoolCompaction.shouldCompact(823117, 411558, 64, 1024),
                "a freshly grown table probes ~2 and must be left alone");
        assertFalse(
                StormPoolCompaction.shouldCompact(23, 0, 64, 1024),
                "tables below minCapacity cannot cost enough to be worth a rehash");
        assertFalse(
                StormPoolCompaction.shouldCompact(2048, 31, 64, 1024),
                "2048/32 = 64 is at the threshold, not over it");
        assertTrue(
                StormPoolCompaction.shouldCompact(2048, 30, 64, 1024),
                "2048/31 = 66 is over the threshold");
    }

    @Test
    void compactUnwindsTheCliffAndPreservesElements() throws Exception {
        Object set = newPoolStyleSet();

        List<Object> live = new ArrayList<>();
        for (int i = 0; i < LIVE_SIZE; i++) {
            Object o = new Object();
            live.add(o);
            add(set, o);
        }

        int freeAfterFill = free(set);
        int inserts = 0;
        while (inserts < CHURN_LIMIT
                && StormPoolCompaction.probeEstimate(capacity(set), free(set)) <= 64) {
            remove(set, live.remove(0));
            Object o = new Object();
            live.add(o);
            add(set, o);
            inserts++;
        }

        int capacity = capacity(set);
        int free = free(set);
        int probe = StormPoolCompaction.probeEstimate(capacity, free);

        assertEquals(LIVE_SIZE, size(set), "churn must not change live size");
        assertTrue(inserts < CHURN_LIMIT, "the cliff must form from ordinary churn alone");
        assertTrue(
                free < freeAfterFill,
                "inserts consume FREE slots: " + free + " should be below " + freeAfterFill);
        assertTrue(
                probe > 64,
                "expected a degenerate probe length, got "
                        + probe
                        + " (capacity="
                        + capacity
                        + ", free="
                        + free
                        + ")");
        assertTrue(
                StormPoolCompaction.shouldCompact(capacity, free, 64, 1024),
                "the sweep must recognise this table as needing compaction");

        Set<Object> expected = new HashSet<>(live);
        compact(set);

        int probeAfter = StormPoolCompaction.probeEstimate(capacity(set), free(set));
        assertTrue(
                probeAfter <= 2,
                "compaction must restore a healthy probe length, got " + probeAfter);
        assertEquals(LIVE_SIZE, size(set), "compaction must not drop elements");
        for (Object o : expected) {
            assertTrue(contains(set, o), "compaction must preserve every live element");
        }
        assertFalse(
                StormPoolCompaction.shouldCompact(capacity(set), free(set), 64, 1024),
                "a compacted table must not immediately re-trigger");
    }

    /**
     * Proves the mechanism is specific to the disabled auto-compaction: the identical churn against
     * a default-configured set never reaches a degenerate probe length.
     */
    @Test
    void defaultAutoCompactionNeverDegenerates() throws Exception {
        Object set = Class.forName(THASHSET).getDeclaredConstructor().newInstance();

        List<Object> live = new ArrayList<>();
        for (int i = 0; i < LIVE_SIZE; i++) {
            Object o = new Object();
            live.add(o);
            add(set, o);
        }
        for (int i = 0; i < CHURN_INSERTS; i++) {
            remove(set, live.remove(0));
            Object o = new Object();
            live.add(o);
            add(set, o);
        }

        int probe = StormPoolCompaction.probeEstimate(capacity(set), free(set));
        assertTrue(probe <= 64, "vanilla Trove defaults stay healthy under churn, got " + probe);
    }

    private static Object newPoolStyleSet() throws Exception {
        Object set = Class.forName(THASHSET).getDeclaredConstructor().newInstance();
        Method setFactor = findMethod(set.getClass(), "setAutoCompactionFactor", float.class);
        setFactor.invoke(set, 0.0F);
        return set;
    }

    private static void add(Object set, Object o) throws Exception {
        ((Set<Object>) set).add(o);
    }

    private static void remove(Object set, Object o) throws Exception {
        ((Set<Object>) set).remove(o);
    }

    private static boolean contains(Object set, Object o) {
        return ((Set<?>) set).contains(o);
    }

    private static int size(Object set) {
        return ((Set<?>) set).size();
    }

    private static void compact(Object set) throws Exception {
        set.getClass().getMethod("compact").invoke(set);
    }

    private static int capacity(Object set) throws Exception {
        return ((Object[]) field(set, "_set").get(set)).length;
    }

    private static int free(Object set) throws Exception {
        return field(set, "_free").getInt(set);
    }

    private static Field field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // declared on a Trove base class
            }
        }
        throw new IllegalStateException("No field " + name);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... args) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, args);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // declared on a Trove base class
            }
        }
        throw new IllegalStateException("No method " + name);
    }
}
