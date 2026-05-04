package io.pzstorm.storm.metrics;

import io.pzstorm.storm.logging.StormLogger;
import java.util.concurrent.atomic.AtomicLong;

public final class CellObjectRemoveMetrics {

    private static final long REPORT_WINDOW_MS = 60_000L;

    public static final int VANILLA_SAMPLE_MASK = 1023;

    private static final AtomicLong fastNanos = new AtomicLong();
    private static final AtomicLong fastCalls = new AtomicLong();
    private static final AtomicLong vanillaSimNanos = new AtomicLong();
    private static final AtomicLong vanillaSimSamples = new AtomicLong();
    private static final AtomicLong tickCount = new AtomicLong();
    private static volatile long windowStartMs = System.currentTimeMillis();

    static {
        Thread reporter =
                new Thread(CellObjectRemoveMetrics::reporterLoop, "StormCellObjectRemoveMetrics");
        reporter.setDaemon(true);
        reporter.start();
    }

    private CellObjectRemoveMetrics() {}

    public static void recordFastNanos(long nanos) {
        fastNanos.addAndGet(nanos);
        fastCalls.incrementAndGet();
    }

    public static void recordVanillaSimulatedNanos(long nanos) {
        vanillaSimNanos.addAndGet(nanos);
        vanillaSimSamples.incrementAndGet();
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
                StormLogger.LOGGER.warn("CellObjectRemoveMetrics reporter failed", t);
            }
        }
    }

    private static void report() {
        long fNanos = fastNanos.getAndSet(0L);
        long fCalls = fastCalls.getAndSet(0L);
        long vNanos = vanillaSimNanos.getAndSet(0L);
        long vSamples = vanillaSimSamples.getAndSet(0L);
        long ticks = tickCount.getAndSet(0L);
        long now = System.currentTimeMillis();
        long windowMs = now - windowStartMs;
        windowStartMs = now;

        if (ticks == 0L && fCalls == 0L) {
            StormLogger.LOGGER.info(
                    "CellObjectRemoveMetrics: window={}ms ticks=0 calls=0 (no activity)", windowMs);
            return;
        }

        double fastAvgUs = fCalls == 0L ? 0.0 : fNanos / 1_000.0 / fCalls;
        double vanillaAvgUs = vSamples == 0L ? 0.0 : vNanos / 1_000.0 / vSamples;
        double ratio = fastAvgUs == 0.0 ? 0.0 : vanillaAvgUs / fastAvgUs;
        double projectedVanillaTotalMs = vanillaAvgUs * fCalls / 1_000.0;
        double fastTotalMs = fNanos / 1_000_000.0;

        StormLogger.LOGGER.info(
                "CellObjectRemoveMetrics: window={}ms ticks={} calls={} fastAvgUs={}"
                        + " fastTotalMs={} vanillaSamples={} vanillaAvgUs={}"
                        + " projectedVanillaTotalMs={} speedup={}x",
                windowMs,
                ticks,
                fCalls,
                String.format("%.3f", fastAvgUs),
                String.format("%.2f", fastTotalMs),
                vSamples,
                String.format("%.2f", vanillaAvgUs),
                String.format("%.2f", projectedVanillaTotalMs),
                String.format("%.1f", ratio));
    }
}
