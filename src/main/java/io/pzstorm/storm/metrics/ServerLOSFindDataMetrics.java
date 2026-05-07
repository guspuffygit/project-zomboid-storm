package io.pzstorm.storm.metrics;

import io.pzstorm.storm.cache.ServerLOSPlayerDataCache;
import io.pzstorm.storm.logging.StormLogger;
import java.util.concurrent.atomic.AtomicLong;

public final class ServerLOSFindDataMetrics {

    private static final long REPORT_WINDOW_MS = 60_000L;

    private static final AtomicLong hits = new AtomicLong();
    private static final AtomicLong misses = new AtomicLong();
    private static volatile long windowStartMs = System.currentTimeMillis();

    static {
        Thread reporter =
                new Thread(ServerLOSFindDataMetrics::reporterLoop, "StormServerLOSFindDataMetrics");
        reporter.setDaemon(true);
        reporter.start();
    }

    private ServerLOSFindDataMetrics() {}

    public static void recordHit() {
        hits.incrementAndGet();
    }

    public static void recordMiss() {
        misses.incrementAndGet();
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
                StormLogger.LOGGER.warn("ServerLOSFindDataMetrics reporter failed", t);
            }
        }
    }

    private static void report() {
        long h = hits.getAndSet(0L);
        long m = misses.getAndSet(0L);
        long now = System.currentTimeMillis();
        long windowMs = now - windowStartMs;
        windowStartMs = now;

        long total = h + m;
        if (total == 0L) {
            StormLogger.LOGGER.info(
                    "ServerLOSFindDataMetrics: window={}ms lookups=0 (no activity)", windowMs);
            return;
        }

        double hitRate = (double) h / total * 100.0;
        StormLogger.LOGGER.info(
                "ServerLOSFindDataMetrics: window={}ms lookups={} hits={} misses={} hitRate={}%"
                        + " cacheSize={}",
                windowMs,
                total,
                h,
                m,
                String.format("%.2f", hitRate),
                ServerLOSPlayerDataCache.size());
    }
}
