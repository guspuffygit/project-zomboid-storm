package io.pzstorm.storm.metrics;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import zombie.network.ClientChunkRequest;

public final class ChunkSaveCache {

    static final String ENABLED_PROPERTY = "storm.chunkSaveCache.enabled";

    private static final ConcurrentMap<Long, byte[]> CACHE = new ConcurrentHashMap<>();

    private ChunkSaveCache() {}

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY, "false"));
    }

    static void resetForTest() {
        CACHE.clear();
    }

    private static long key(int wx, int wy) {
        return ((long) wx << 32) | (wy & 0xFFFFFFFFL);
    }

    public static boolean populate(int wx, int wy, ClientChunkRequest.Chunk ccrc) {
        if (ccrc == null) {
            return false;
        }
        byte[] cached = CACHE.get(key(wx, wy));
        if (cached == null) {
            return false;
        }
        ByteBuffer bb = ccrc.bb;
        if (bb == null || bb.capacity() < cached.length) {
            bb = ByteBuffer.allocate(Math.max(cached.length, 16384));
            ccrc.bb = bb;
        }
        bb.clear();
        bb.put(cached, 0, cached.length);
        return true;
    }

    public static void store(int wx, int wy, ByteBuffer bb) {
        if (bb == null) {
            return;
        }
        int len = bb.position();
        if (len <= 0 || !bb.hasArray()) {
            return;
        }
        byte[] copy = new byte[len];
        System.arraycopy(bb.array(), 0, copy, 0, len);
        CACHE.put(key(wx, wy), copy);
    }

    public static byte[] peek(int wx, int wy) {
        return CACHE.get(key(wx, wy));
    }

    public static void invalidate(int wx, int wy) {
        CACHE.remove(key(wx, wy));
    }

    public static int size() {
        return CACHE.size();
    }
}
