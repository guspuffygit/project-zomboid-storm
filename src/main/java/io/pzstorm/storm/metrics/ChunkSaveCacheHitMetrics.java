package io.pzstorm.storm.metrics;

import io.pzstorm.storm.logging.StormLogger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkSaveCacheHitMetrics {

    private static final long REPORT_WINDOW_MS = 60_000L;

    private static final ConcurrentHashMap<Long, ChunkState> CHUNKS = new ConcurrentHashMap<>();

    private static final AtomicLong windowHits = new AtomicLong();
    private static final AtomicLong windowMisses = new AtomicLong();
    private static final AtomicLong windowFirsts = new AtomicLong();
    private static final AtomicLong windowCalls = new AtomicLong();

    private static final AtomicLong cleanHits = new AtomicLong();
    private static final AtomicLong cleanMisses = new AtomicLong();
    private static final AtomicLong dirtyHits = new AtomicLong();
    private static final AtomicLong dirtyMisses = new AtomicLong();

    private static volatile long windowStartMs = System.currentTimeMillis();

    static {
        Thread reporter =
                new Thread(ChunkSaveCacheHitMetrics::reporterLoop, "StormChunkSaveCacheHitMetrics");
        reporter.setDaemon(true);
        reporter.start();
    }

    private ChunkSaveCacheHitMetrics() {}

    public static boolean observe(int wx, int wy, long crc, boolean wasClean) {
        windowCalls.incrementAndGet();
        long key = ((long) wx << 32) | (wy & 0xFFFFFFFFL);
        ChunkState fresh = null;
        ChunkState prev = CHUNKS.get(key);
        if (prev == null) {
            fresh = new ChunkState();
            fresh.lastCrc = crc;
            fresh.firsts = 1L;
            ChunkState raced = CHUNKS.putIfAbsent(key, fresh);
            if (raced == null) {
                windowFirsts.incrementAndGet();
                return false;
            }
            prev = raced;
        }
        synchronized (prev) {
            if (prev.lastCrc == crc) {
                prev.hits++;
                windowHits.incrementAndGet();
                if (wasClean) {
                    cleanHits.incrementAndGet();
                } else {
                    dirtyHits.incrementAndGet();
                }
                return false;
            } else {
                prev.misses++;
                prev.lastCrc = crc;
                windowMisses.incrementAndGet();
                if (wasClean) {
                    cleanMisses.incrementAndGet();
                    return true;
                } else {
                    dirtyMisses.incrementAndGet();
                    return false;
                }
            }
        }
    }

    private static void reporterLoop() {
        while (true) {
            try {
                Thread.sleep(REPORT_WINDOW_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                report();
            } catch (Throwable t) {
                StormLogger.LOGGER.warn("ChunkSaveCacheHitMetrics reporter failed", t);
            }
        }
    }

    private static void report() {
        long calls = windowCalls.getAndSet(0L);
        long hits = windowHits.getAndSet(0L);
        long misses = windowMisses.getAndSet(0L);
        long firsts = windowFirsts.getAndSet(0L);
        long ch = cleanHits.getAndSet(0L);
        long cm = cleanMisses.getAndSet(0L);
        long dh = dirtyHits.getAndSet(0L);
        long dm = dirtyMisses.getAndSet(0L);
        long now = System.currentTimeMillis();
        long windowMs = now - windowStartMs;
        windowStartMs = now;

        if (calls == 0L) {
            StormLogger.LOGGER.info(
                    "ChunkSaveCacheHitMetrics: window={}ms calls=0 (no SaveLoadedChunk activity)",
                    windowMs);
            return;
        }

        long denom = hits + misses;
        double hitRate = denom == 0L ? 0.0 : 100.0 * hits / denom;

        int[] buckets = new int[10];
        int chunksMulti = 0;
        long distinctChunks = CHUNKS.size();
        for (Map.Entry<Long, ChunkState> e : CHUNKS.entrySet()) {
            ChunkState s = e.getValue();
            long h;
            long m;
            synchronized (s) {
                h = s.hits;
                m = s.misses;
            }
            long total = h + m;
            if (total < 2L) {
                continue;
            }
            chunksMulti++;
            double r = (double) h / total;
            int b = (int) Math.floor(r * 10.0);
            if (b > 9) {
                b = 9;
            }
            buckets[b]++;
        }

        StringBuilder bucketStr = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i > 0) {
                bucketStr.append(",");
            }
            bucketStr.append(buckets[i]);
        }

        StormLogger.LOGGER.info(
                "ChunkSaveCacheHitMetrics: window={}ms calls={} firsts={} hits={} misses={}"
                        + " windowHitRate={}% distinctChunks={} chunksObservedMultipleTimes={}"
                        + " perChunkHitRateBuckets[0-10..90-100]=[{}]"
                        + " crossTab[cleanHit={} cleanMiss={} dirtyHit={} dirtyMiss={}]",
                windowMs,
                calls,
                firsts,
                hits,
                misses,
                String.format("%.1f", hitRate),
                distinctChunks,
                chunksMulti,
                bucketStr.toString(),
                ch,
                cm,
                dh,
                dm);
    }

    private static final class ChunkState {
        long lastCrc;
        long hits;
        long misses;
        long firsts;
    }
}
