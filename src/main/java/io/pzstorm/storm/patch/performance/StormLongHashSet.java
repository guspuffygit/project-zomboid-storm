package io.pzstorm.storm.patch.performance;

import java.util.Arrays;

/**
 * Minimal open-addressed hash set of primitive {@code long} keys for the per-tick hot paths in
 * {@link StormPlayerInfluenceGrid}. {@code HashSet<Long>} boxes a {@code Long} on every {@code
 * contains}/{@code add}; the influence grid performs on the order of 100k such lookups per tick
 * (candidate rasterization across ~100 connections plus one {@code containsCell} per loaded cell
 * and a (2·margin+1)² neighborhood probe per eviction candidate), which profiling on ATF production
 * (2026-08-24) attributed ~3 ms of a ~62 ms tick to. This set allocates nothing after growth
 * settles.
 *
 * <p>Linear probing over a power-of-two table at ≤0.5 load; {@code 0} is the empty-slot sentinel,
 * with a separate flag so the valid key {@code 0} still works. Not thread-safe — server main thread
 * only, like its caller.
 */
final class StormLongHashSet {

    private long[] keys = new long[64];
    private int size;
    private boolean hasZero;

    /** Returns {@code true} if the key was not already present. */
    boolean add(long key) {
        if (key == 0L) {
            if (hasZero) {
                return false;
            }
            hasZero = true;
            return true;
        }
        if ((size + 1) * 2 > keys.length) {
            grow();
        }
        int mask = keys.length - 1;
        int i = mix(key) & mask;
        while (true) {
            long cur = keys[i];
            if (cur == 0L) {
                keys[i] = key;
                size++;
                return true;
            }
            if (cur == key) {
                return false;
            }
            i = (i + 1) & mask;
        }
    }

    boolean contains(long key) {
        if (key == 0L) {
            return hasZero;
        }
        int mask = keys.length - 1;
        int i = mix(key) & mask;
        while (true) {
            long cur = keys[i];
            if (cur == 0L) {
                return false;
            }
            if (cur == key) {
                return true;
            }
            i = (i + 1) & mask;
        }
    }

    void clear() {
        Arrays.fill(keys, 0L);
        size = 0;
        hasZero = false;
    }

    int size() {
        return size + (hasZero ? 1 : 0);
    }

    private void grow() {
        long[] old = keys;
        keys = new long[old.length * 2];
        int mask = keys.length - 1;
        for (long key : old) {
            if (key != 0L) {
                int i = mix(key) & mask;
                while (keys[i] != 0L) {
                    i = (i + 1) & mask;
                }
                keys[i] = key;
            }
        }
    }

    // Stafford variant 13 finalizer: full-avalanche so packed (wx,wy) coordinate keys spread even
    // though they differ only in a few low bits of each half.
    private static int mix(long key) {
        long h = key;
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        return (int) (h ^ (h >>> 31));
    }
}
