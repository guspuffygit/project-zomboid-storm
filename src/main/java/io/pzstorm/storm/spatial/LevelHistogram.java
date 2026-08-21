package io.pzstorm.storm.spatial;

import java.util.Arrays;

/**
 * Per-z-level object counter for the shared spatial index: {@link #add} one object per level, then
 * {@link #sumNear} asks how many sit within ±{@code radius} levels of a reference level. Levels
 * outside {@link #MIN_LEVEL}..{@link #MAX_LEVEL} are clamped into the range so the histogram can
 * never throw on an out-of-world z.
 */
public final class LevelHistogram {

    public static final int MIN_LEVEL = -64;
    public static final int MAX_LEVEL = 63;

    private final int[] counts = new int[MAX_LEVEL - MIN_LEVEL + 1];
    private int total;

    public void clear() {
        Arrays.fill(counts, 0);
        total = 0;
    }

    public void add(int level) {
        counts[index(level)]++;
        total++;
    }

    /** Objects on levels {@code level - radius} .. {@code level + radius}, inclusive. */
    public int sumNear(int level, int radius) {
        int lo = index(level - radius);
        int hi = index(level + radius);
        int sum = 0;
        for (int i = lo; i <= hi; i++) {
            sum += counts[i];
        }
        return sum;
    }

    public int total() {
        return total;
    }

    /** Floor of a float z to the integer level it belongs to. */
    public static int levelOf(float z) {
        return (int) Math.floor(z);
    }

    private static int index(int level) {
        int clamped = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
        return clamped - MIN_LEVEL;
    }
}
