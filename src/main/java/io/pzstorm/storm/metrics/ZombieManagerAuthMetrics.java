package io.pzstorm.storm.metrics;

import io.pzstorm.storm.logging.StormLogger;
import java.util.concurrent.atomic.AtomicLong;

public final class ZombieManagerAuthMetrics {

    private static final long REPORT_WINDOW_MS = 60_000L;

    private static final AtomicLong totalNanos = new AtomicLong();
    private static final AtomicLong callCount = new AtomicLong();
    private static final AtomicLong tickCount = new AtomicLong();
    private static volatile long windowStartMs = System.currentTimeMillis();

    static {
        Thread reporter =
                new Thread(ZombieManagerAuthMetrics::reporterLoop, "StormZombieManagerAuthMetrics");
        reporter.setDaemon(true);
        reporter.start();
    }

    private ZombieManagerAuthMetrics() {}

    public static void recordNanos(long nanos) {
        totalNanos.addAndGet(nanos);
        callCount.incrementAndGet();
    }

    public static void recordTick() {
        tickCount.incrementAndGet();
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
                StormLogger.LOGGER.warn("ZombieManagerAuthMetrics reporter failed", t);
            }
        }
    }

    private static void report() {
        long nanos = totalNanos.getAndSet(0L);
        long calls = callCount.getAndSet(0L);
        long ticks = tickCount.getAndSet(0L);
        long now = System.currentTimeMillis();
        long windowMs = now - windowStartMs;
        windowStartMs = now;

        if (ticks == 0L && calls == 0L) {
            StormLogger.LOGGER.info(
                    "ZombieManagerAuthMetrics: window={}ms ticks=0 calls=0 (no activity)",
                    windowMs);
            return;
        }

        double totalMs = nanos / 1_000_000.0;
        double avgPerTickMs = ticks == 0L ? 0.0 : totalMs / ticks;
        double avgPerCallUs = calls == 0L ? 0.0 : nanos / 1_000.0 / calls;
        double avgCallsPerTick = ticks == 0L ? 0.0 : (double) calls / ticks;

        StormLogger.LOGGER.info(
                "ZombieManagerAuthMetrics: window={}ms ticks={} calls={} totalMs={}"
                        + " avgPerTickMs={} avgPerCallUs={} avgCallsPerTick={}",
                windowMs,
                ticks,
                calls,
                String.format("%.2f", totalMs),
                String.format("%.3f", avgPerTickMs),
                String.format("%.2f", avgPerCallUs),
                String.format("%.1f", avgCallsPerTick));
    }
}
