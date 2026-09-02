package io.pzstorm.storm.scripting;

import java.util.Arrays;

/** Growable {@code long[]} bit set keyed by tag index. Pure functions; no state. */
public final class StormTagMask {

    private StormTagMask() {}

    public static long[] set(long[] mask, int index) {
        int word = index >>> 6;
        if (word >= mask.length) {
            mask = Arrays.copyOf(mask, Math.max(word + 1, mask.length * 2));
        }
        mask[word] |= 1L << (index & 63);
        return mask;
    }

    public static void clear(long[] mask, int index) {
        int word = index >>> 6;
        if (word < mask.length) {
            mask[word] &= ~(1L << (index & 63));
        }
    }

    public static boolean test(long[] mask, int index) {
        int word = index >>> 6;
        return word < mask.length && (mask[word] & (1L << (index & 63))) != 0;
    }
}
