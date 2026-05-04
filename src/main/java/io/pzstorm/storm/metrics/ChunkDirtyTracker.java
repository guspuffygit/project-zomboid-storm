package io.pzstorm.storm.metrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ChunkDirtyTracker {

    private static final ConcurrentMap<Long, Boolean> DIRTY = new ConcurrentHashMap<>();

    private ChunkDirtyTracker() {}

    private static long key(int wx, int wy) {
        return ((long) wx << 32) | (wy & 0xFFFFFFFFL);
    }

    public static void markDirty(int wx, int wy) {
        DIRTY.put(key(wx, wy), Boolean.TRUE);
    }

    public static void markClean(int wx, int wy) {
        DIRTY.put(key(wx, wy), Boolean.FALSE);
    }

    public static boolean isDirty(int wx, int wy) {
        Boolean v = DIRTY.get(key(wx, wy));
        return v == null || v;
    }
}
