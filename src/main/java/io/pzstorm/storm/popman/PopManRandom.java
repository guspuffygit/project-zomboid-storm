package io.pzstorm.storm.popman;

import java.security.SecureRandom;

/**
 * The population's own random number generator — MT19937, drawn from exactly as the native drew
 * from it.
 *
 * <p>Not {@link java.util.Random}: the native population picks spawn squares, chunks and directions
 * from a Mersenne Twister through a Lemire multiply-shift with rejection, and a different generator
 * or a different rejection rule produces a different world from the same seed. The seed itself is
 * taken from the OS, so a run is not reproducible unless one is supplied.
 */
public final class PopManRandom {

    private static final int N = 624;
    private static final int M = 397;
    private static final int MATRIX_A = 0x9908b0df;
    private static final int UPPER_MASK = 0x80000000;
    private static final int LOWER_MASK = 0x7fffffff;

    private final int[] state = new int[N];
    private int index = N + 1;

    public PopManRandom() {
        this(new SecureRandom().nextInt());
    }

    public PopManRandom(int seed) {
        setSeed(seed);
    }

    public void setSeed(int seed) {
        state[0] = seed;
        for (int i = 1; i < N; i++) {
            state[i] = 1812433253 * (state[i - 1] ^ (state[i - 1] >>> 30)) + i;
        }
        index = N;
    }

    /** One raw 32-bit draw, treated as unsigned by every caller. */
    public int nextBits() {
        if (index >= N) {
            twist();
        }
        int y = state[index++];
        y ^= y >>> 11;
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        return y ^ (y >>> 18);
    }

    private void twist() {
        for (int i = 0; i < N; i++) {
            int y = (state[i] & UPPER_MASK) | (state[(i + 1) % N] & LOWER_MASK);
            int next = state[(i + M) % N] ^ (y >>> 1);
            state[i] = (y & 1) == 0 ? next : next ^ MATRIX_A;
        }
        index = 0;
    }

    /**
     * Uniform over {@code 0..bound-1}. A non-positive bound is returned unchanged, which is what
     * the native did rather than throwing.
     */
    public int nextInt(int bound) {
        if (bound <= 0) {
            return bound;
        }
        long unsignedBound = bound & 0xFFFFFFFFL;
        long product = (nextBits() & 0xFFFFFFFFL) * unsignedBound;
        long low = product & 0xFFFFFFFFL;
        if (low < unsignedBound) {
            long threshold = (-unsignedBound & 0xFFFFFFFFL) % unsignedBound;
            while (low < threshold) {
                product = (nextBits() & 0xFFFFFFFFL) * unsignedBound;
                low = product & 0xFFFFFFFFL;
            }
        }
        return (int) (product >>> 32);
    }

    /** Uniform over the half-open range between the two bounds, whichever order they arrive in. */
    public int nextRange(int a, int b) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);
        return min + nextInt(max - min);
    }

    /** Uniform over {@code [0, 1)}. */
    public float nextUnitFloat() {
        return (nextBits() >>> 8) / (float) (1 << 24);
    }

    /**
     * Uniform over the half-open range between the two bounds, whichever order they arrive in. The
     * lower bound is reachable and the upper one is not, so passing the bounds the other way round
     * changes which end can come out.
     */
    public float nextFloat(float a, float b) {
        if (a == b) {
            return a;
        }
        float min = Math.min(a, b);
        float max = Math.max(a, b);
        return min + (max - min) * nextUnitFloat();
    }
}
