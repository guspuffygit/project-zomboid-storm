// Port of glibc stdlib/random_r.c (srandom_r / random_r, TYPE_3: x**31 + x**3 + 1, degree 31,
// separation 3) as used by rand() and srand(). Identical in glibc 2.35 and 2.39.
package io.pzstorm.storm.bullet.libm;

/**
 * glibc {@code rand()}/{@code srand()}. A process that never calls {@code srand} gets the same
 * sequence as {@code srand(1)}. Not thread-safe (glibc's rand takes a lock; callers here are
 * single-threaded).
 */
public final class GlibcRand {

    private static final int DEG = 31;
    private static final int SEP = 3;

    /** Process-wide generator, equivalent to glibc's static state (initially seeded with 1). */
    private static final GlibcRand DEFAULT = new GlibcRand(1);

    private final int[] state = new int[DEG];
    private int fptr;
    private int rptr;

    public GlibcRand(int seed) {
        seed(seed);
    }

    /** srandom_r(seed). */
    public void seed(int seed) {
        if (seed == 0) {
            seed = 1;
        }
        state[0] = seed;
        int word = seed;
        for (int i = 1; i < DEG; ++i) {
            // state[i] = (16807 * state[i - 1]) % 2147483647 without overflow (Schrage).
            int hi = word / 127773;
            int lo = word % 127773;
            word = 16807 * lo - 2836 * hi;
            if (word < 0) {
                word += 2147483647;
            }
            state[i] = word;
        }
        fptr = SEP;
        rptr = 0;
        for (int kc = DEG * 10; --kc >= 0; ) {
            next();
        }
    }

    /** random_r: a value in [0, 2^31). */
    public int next() {
        int val = state[fptr] += state[rptr];
        int result = val >>> 1;
        if (++fptr >= DEG) {
            fptr = 0;
            ++rptr;
        } else if (++rptr >= DEG) {
            rptr = 0;
        }
        return result;
    }

    /** glibc rand(). */
    public static int rand() {
        synchronized (DEFAULT) {
            return DEFAULT.next();
        }
    }

    /** glibc srand(seed). */
    public static void srand(int seed) {
        synchronized (DEFAULT) {
            DEFAULT.seed(seed);
        }
    }
}
