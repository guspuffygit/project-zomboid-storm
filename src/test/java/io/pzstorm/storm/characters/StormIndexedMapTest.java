package io.pzstorm.storm.characters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.entity.StormIndexedKeyHolder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link StormIndexedMap} stands in for the {@code HashMap}/{@code LinkedHashMap} behind {@code
 * Stats}, {@code Moodles} and {@code CharacterTraits}, so it must honour every {@code Map} idiom
 * those classes use: insertion-ordered iteration, write-through entries, {@code getOrDefault},
 * {@code values()} walks, and unknown keys.
 */
class StormIndexedMapTest implements UnitTest {

    private static final class Key implements StormIndexedKeyHolder {
        final int index;
        final String name;

        Key(int index, String name) {
            this.index = index;
            this.name = name;
        }

        @Override
        public int getStormIndex() {
            return index;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final Key A = new Key(0, "a");
    private static final Key B = new Key(1, "b");
    private static final Key C = new Key(7, "c");
    private static final Key D = new Key(3, "d");

    @Test
    void getPutAndDefaults() {
        StormIndexedMap map = new StormIndexedMap();
        assertTrue(map.isEmpty());
        assertNull(map.get(A));
        assertEquals(1.5F, map.getOrDefault(A, 1.5F));
        assertNull(map.put(A, 2F));
        assertEquals(2F, map.put(A, 3F));
        assertEquals(3F, map.get(A));
        assertEquals(3F, map.getOrDefault(A, 1.5F));
        assertEquals(3F, map.getIndexed(A));
        assertNull(map.getIndexed(C));
        assertTrue(map.containsKey(A));
        assertFalse(map.containsKey(C));
        assertEquals(1, map.size());
        assertThrows(NullPointerException.class, () -> map.put(B, null));
    }

    @Test
    void iterationFollowsInsertionOrderAndReusesSlots() {
        StormIndexedMap map = new StormIndexedMap();
        map.put(C, 1);
        map.put(A, 2);
        map.put(D, 3);
        assertEquals(List.of(C, A, D), keys(map));
        assertEquals(List.of(1, 2, 3), new ArrayList<>(map.values()));
        map.remove(A);
        assertEquals(List.of(C, D), keys(map));
        assertEquals(2, map.size());
        map.put(A, 9);
        assertEquals(List.of(C, A, D), keys(map), "a re-put key keeps its original slot");
        map.put(B, 4);
        assertEquals(List.of(C, A, D, B), keys(map));
        assertEquals(4, map.size());
    }

    @Test
    void entriesWriteThroughLikeLinkedHashMapReset() {
        StormIndexedMap map = new StormIndexedMap();
        map.put(A, true);
        map.put(B, true);
        for (Map.Entry<Object, Object> e : map.entrySet()) {
            assertEquals(true, e.setValue(false));
        }
        assertEquals(false, map.get(A));
        assertEquals(false, map.get(B));
        Map<Object, Object> expected = new HashMap<>();
        expected.put(A, false);
        expected.put(B, false);
        assertEquals(expected, map);
        assertEquals(expected.hashCode(), map.hashCode());
    }

    @Test
    void iteratorRemoveAndClear() {
        StormIndexedMap map = new StormIndexedMap();
        map.put(A, 1);
        map.put(B, 2);
        map.put(C, 3);
        Iterator<Map.Entry<Object, Object>> it = map.entrySet().iterator();
        it.next();
        it.remove();
        assertEquals(List.of(B, C), keys(map));
        assertEquals(2, map.size());
        assertTrue(map.containsValue(3));
        assertFalse(map.containsValue(1));
        map.clear();
        assertTrue(map.isEmpty());
        assertEquals(List.of(), keys(map));
        map.put(B, 5);
        assertEquals(List.of(B), keys(map));
    }

    @Test
    void nonIndexedKeysFallBackToOverflow() {
        StormIndexedMap map = new StormIndexedMap();
        map.put(A, 1);
        map.put("plain", 2);
        map.put(B, 3);
        assertEquals(2, map.get("plain"));
        assertEquals(2, map.getOrDefault("plain", 0));
        assertEquals(0, map.getOrDefault("missing", 0));
        assertEquals(2, map.getIndexed("plain"));
        assertTrue(map.containsKey("plain"));
        assertEquals(3, map.size());
        assertEquals(List.of(A, B, "plain"), keys(map), "indexed slots first, then overflow");
        assertEquals(2, map.remove("plain"));
        assertEquals(2, map.size());
        assertEquals(List.of(A, B), keys(map));
    }

    @Test
    void growsPastInitialCapacity() {
        StormIndexedMap map = new StormIndexedMap();
        List<Key> keys = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            Key k = new Key(299 - i, "k" + i);
            keys.add(k);
            map.put(k, i);
        }
        assertEquals(300, map.size());
        for (int i = 0; i < 300; i++) {
            assertEquals(i, map.get(keys.get(i)));
        }
        assertEquals(keys, keys(map));
    }

    private static List<Object> keys(Map<Object, Object> map) {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<Object, Object> e : map.entrySet()) {
            out.add(e.getKey());
        }
        return out;
    }
}
