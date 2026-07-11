package io.pzstorm.storm.mapscan;

/**
 * Packs a world square coordinate {@code (x, y, z)} into a single {@code long}.
 *
 * <p>x and y use 24 bits each (world squares run 0..~25k on the current map), z is stored with a
 * +512 bias in 16 bits so basement levels (negative z) pack cleanly.
 */
public final class SquareCoord {

    private static final int Z_BIAS = 512;

    private SquareCoord() {}

    public static long pack(int x, int y, int z) {
        return ((long) x << 40) | ((long) y << 16) | (z + Z_BIAS);
    }

    public static int unpackX(long packed) {
        return (int) (packed >>> 40);
    }

    public static int unpackY(long packed) {
        return (int) ((packed >>> 16) & 0xFFFFFF);
    }

    public static int unpackZ(long packed) {
        return (int) (packed & 0xFFFF) - Z_BIAS;
    }
}
