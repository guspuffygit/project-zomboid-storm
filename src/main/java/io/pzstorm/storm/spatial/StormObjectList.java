package io.pzstorm.storm.spatial;

import java.util.Arrays;

/**
 * Minimal growable {@code Object[]} used as the output sink of {@link StormChunkIndex} queries.
 * Callers keep one instance per consumer (main thread only) so a query allocates nothing in the
 * steady state.
 */
public final class StormObjectList {

    private Object[] items;
    private int size;

    public StormObjectList(int initialCapacity) {
        items = new Object[Math.max(8, initialCapacity)];
    }

    public int size() {
        return size;
    }

    public Object get(int index) {
        return items[index];
    }

    public void add(Object o) {
        if (size == items.length) {
            items = Arrays.copyOf(items, items.length << 1);
        }
        items[size++] = o;
    }

    /** Drops every element and nulls the slots so stale world objects are not pinned. */
    public void clear() {
        Arrays.fill(items, 0, size, null);
        size = 0;
    }
}
