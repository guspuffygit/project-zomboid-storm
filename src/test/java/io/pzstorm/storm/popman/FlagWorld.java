package io.pzstorm.storm.popman;

import java.util.HashMap;
import java.util.Map;

/**
 * A map painted square by square in raw collision flags, so that tests can express walls — which
 * belong to one side of a boundary — rather than only "blocked" and "open".
 */
class FlagWorld implements PopManWorld {

    private final Map<Long, Integer> flags = new HashMap<>();
    int density = 128;

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    FlagWorld set(int x, int y, int bits) {
        flags.merge(key(x, y), bits, (a, b) -> a | b);
        return this;
    }

    /** A closed box around a single square, in the convention where walls belong to the square. */
    FlagWorld box(int minX, int minY, int maxX, int maxY) {
        for (int x = minX; x <= maxX; x++) {
            set(x, minY, PopManMap.BIT_WALL_N);
            set(x, maxY + 1, PopManMap.BIT_WALL_N);
        }
        for (int y = minY; y <= maxY; y++) {
            set(minX, y, PopManMap.BIT_WALL_W);
            set(maxX + 1, y, PopManMap.BIT_WALL_W);
        }
        return this;
    }

    @Override
    public int squareFlags(int squareX, int squareY) {
        return flags.getOrDefault(key(squareX, squareY), 0);
    }

    @Override
    public int densityByte(int chunkX, int chunkY) {
        return density;
    }
}
