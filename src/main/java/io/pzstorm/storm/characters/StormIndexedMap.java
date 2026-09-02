package io.pzstorm.storm.characters;

import io.pzstorm.storm.entity.StormIndexedKeyHolder;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Array-backed replacement for the per-character {@code HashMap<CharacterStat, Float>}, {@code
 * HashMap<MoodleType, Moodle>} and {@code LinkedHashMap<CharacterTrait, Boolean>} ({@code Stats},
 * {@code Moodles}, {@code CharacterTraits}). Keys that carry a {@link StormIndexedKeyHolder} index
 * live in a slot array, so {@code get}/{@code put}/{@code containsKey} are one interface call and
 * one array access instead of an identity-hash bucket probe; together those three probes were ~2.5%
 * of player update on ATF prod (scan #10, 2026-09-02).
 *
 * <p>Semantics preserved from the vanilla maps: iteration is in first-insertion order (what the
 * {@code LinkedHashMap} guaranteed for traits and what {@code HashMap} happened to give the
 * registry-order constructor loops), a re-{@code put} of a live key keeps its position, entries
 * from {@link #entrySet()} write through on {@code setValue} ({@code CharacterTraits.reset} relies
 * on it), and {@link #values()} iterates without allocating per element ({@code Moodles.Update}
 * walks it every tick). Null values are rejected (none of the three maps ever stores one; a null
 * slot is how absence is represented). Keys without an index — null, or an instance built before
 * the index patch applied — fall through to a lazily created {@link HashMap}, so an unpatched key
 * class degrades to vanilla behaviour rather than to wrong answers.
 *
 * <p>Removal leaves the key's position in the order list as a tombstone (skipped on iteration and
 * reused on re-insertion), so the order list is bounded by the number of distinct keys ever put.
 * Single-threaded by contract, like the maps it replaces.
 */
public final class StormIndexedMap extends AbstractMap<Object, Object> {

    private static final int INITIAL_CAPACITY = 32;

    private Object[] keys = new Object[INITIAL_CAPACITY];
    private Object[] values = new Object[INITIAL_CAPACITY];
    private boolean[] ordered = new boolean[INITIAL_CAPACITY];
    private int[] order = new int[INITIAL_CAPACITY];
    private int orderCount;
    private int indexedSize;
    private HashMap<Object, Object> overflow;

    private EntrySet entrySet;
    private Values valuesView;

    private static int indexOf(Object key) {
        if (key instanceof StormIndexedKeyHolder) {
            return ((StormIndexedKeyHolder) key).getStormIndex();
        }
        return -1;
    }

    /** {@link #get} without the {@code Map} interface dispatch; {@code null} when absent. */
    public Object getIndexed(Object key) {
        int idx = indexOf(key);
        if (idx < 0) {
            return overflow == null ? null : overflow.get(key);
        }
        return idx < values.length ? values[idx] : null;
    }

    @Override
    public Object get(Object key) {
        return getIndexed(key);
    }

    @Override
    public Object getOrDefault(Object key, Object defaultValue) {
        Object v = getIndexed(key);
        return v != null ? v : defaultValue;
    }

    @Override
    public boolean containsKey(Object key) {
        return getIndexed(key) != null;
    }

    @Override
    public Object put(Object key, Object value) {
        Objects.requireNonNull(value, "StormIndexedMap does not store null values");
        int idx = indexOf(key);
        if (idx < 0) {
            if (overflow == null) {
                overflow = new HashMap<>();
            }
            return overflow.put(key, value);
        }
        if (idx >= values.length) {
            grow(idx + 1);
        }
        Object old = values[idx];
        values[idx] = value;
        keys[idx] = key;
        if (old == null) {
            indexedSize++;
            if (!ordered[idx]) {
                ordered[idx] = true;
                if (orderCount == order.length) {
                    order = Arrays.copyOf(order, orderCount << 1);
                }
                order[orderCount++] = idx;
            }
        }
        return old;
    }

    @Override
    public Object remove(Object key) {
        int idx = indexOf(key);
        if (idx < 0) {
            return overflow == null ? null : overflow.remove(key);
        }
        if (idx >= values.length) {
            return null;
        }
        Object old = values[idx];
        if (old != null) {
            values[idx] = null;
            indexedSize--;
        }
        return old;
    }

    @Override
    public int size() {
        return indexedSize + (overflow == null ? 0 : overflow.size());
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public void clear() {
        Arrays.fill(keys, null);
        Arrays.fill(values, null);
        Arrays.fill(ordered, false);
        orderCount = 0;
        indexedSize = 0;
        overflow = null;
    }

    @Override
    public boolean containsValue(Object value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < orderCount; i++) {
            if (value.equals(values[order[i]])) {
                return true;
            }
        }
        return overflow != null && overflow.containsValue(value);
    }

    @Override
    public Set<Map.Entry<Object, Object>> entrySet() {
        EntrySet es = entrySet;
        if (es == null) {
            es = new EntrySet();
            entrySet = es;
        }
        return es;
    }

    @Override
    public Collection<Object> values() {
        Values vs = valuesView;
        if (vs == null) {
            vs = new Values();
            valuesView = vs;
        }
        return vs;
    }

    private void grow(int minCapacity) {
        int cap = values.length;
        while (cap < minCapacity) {
            cap <<= 1;
        }
        keys = Arrays.copyOf(keys, cap);
        values = Arrays.copyOf(values, cap);
        ordered = Arrays.copyOf(ordered, cap);
    }

    private final class IndexedEntry implements Map.Entry<Object, Object> {
        private final int idx;

        IndexedEntry(int idx) {
            this.idx = idx;
        }

        @Override
        public Object getKey() {
            return keys[idx];
        }

        @Override
        public Object getValue() {
            return values[idx];
        }

        @Override
        public Object setValue(Object value) {
            Objects.requireNonNull(value, "StormIndexedMap does not store null values");
            Object old = values[idx];
            values[idx] = value;
            if (old == null) {
                indexedSize++;
            }
            return old;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            return Objects.equals(getKey(), e.getKey()) && Objects.equals(getValue(), e.getValue());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
        }

        @Override
        public String toString() {
            return getKey() + "=" + getValue();
        }
    }

    /** Walks live indexed slots in insertion order, then the overflow map. */
    private abstract class SlotIterator<T> implements Iterator<T> {
        private int pos;
        private int lastIdx = -1;
        private Iterator<Map.Entry<Object, Object>> overflowIt;
        private boolean lastWasOverflow;

        SlotIterator() {
            skipDead();
        }

        private void skipDead() {
            while (pos < orderCount && values[order[pos]] == null) {
                pos++;
            }
            if (pos >= orderCount && overflowIt == null && overflow != null) {
                overflowIt = overflow.entrySet().iterator();
            }
        }

        @Override
        public boolean hasNext() {
            return pos < orderCount || (overflowIt != null && overflowIt.hasNext());
        }

        @Override
        public T next() {
            if (pos < orderCount) {
                int idx = order[pos++];
                lastIdx = idx;
                lastWasOverflow = false;
                skipDead();
                return fromSlot(idx);
            }
            if (overflowIt != null && overflowIt.hasNext()) {
                lastWasOverflow = true;
                lastIdx = -1;
                return fromOverflow(overflowIt.next());
            }
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            if (lastWasOverflow) {
                overflowIt.remove();
                lastWasOverflow = false;
                return;
            }
            if (lastIdx < 0 || values[lastIdx] == null) {
                throw new IllegalStateException();
            }
            values[lastIdx] = null;
            indexedSize--;
            lastIdx = -1;
        }

        abstract T fromSlot(int idx);

        abstract T fromOverflow(Map.Entry<Object, Object> e);
    }

    private final class EntrySet extends AbstractSet<Map.Entry<Object, Object>> {
        @Override
        public Iterator<Map.Entry<Object, Object>> iterator() {
            return new SlotIterator<Map.Entry<Object, Object>>() {
                @Override
                Map.Entry<Object, Object> fromSlot(int idx) {
                    return new IndexedEntry(idx);
                }

                @Override
                Map.Entry<Object, Object> fromOverflow(Map.Entry<Object, Object> e) {
                    return e;
                }
            };
        }

        @Override
        public int size() {
            return StormIndexedMap.this.size();
        }

        @Override
        public void clear() {
            StormIndexedMap.this.clear();
        }
    }

    private final class Values extends AbstractCollection<Object> {
        @Override
        public Iterator<Object> iterator() {
            return new SlotIterator<Object>() {
                @Override
                Object fromSlot(int idx) {
                    return values[idx];
                }

                @Override
                Object fromOverflow(Map.Entry<Object, Object> e) {
                    return e.getValue();
                }
            };
        }

        @Override
        public int size() {
            return StormIndexedMap.this.size();
        }

        @Override
        public void clear() {
            StormIndexedMap.this.clear();
        }
    }
}
