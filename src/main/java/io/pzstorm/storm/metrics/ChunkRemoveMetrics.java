package io.pzstorm.storm.metrics;

import io.pzstorm.storm.logging.StormLogger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Accumulates per-tick CPU time spent in {@code IsoChunk.removeFromWorld()} on the main thread and
 * reports a rolling 60-second average.
 *
 * <p>Wired by:
 *
 * <ul>
 *   <li>{@code IsoChunkRemoveFromWorldAdvice} &mdash; calls {@link #recordRemoveNanos(long)} for
 *       each {@code IsoChunk.removeFromWorld()} invocation on the server main thread.
 *   <li>{@code MovingObjectUpdateSchedulerStartFrameAdvice} &mdash; calls {@link #recordTick()}
 *       once per server tick.
 * </ul>
 *
 * <p>A daemon reporter thread snapshots and resets the counters every 60s and logs the result via
 * {@link StormLogger#LOGGER} at INFO level. Reporter starts on first class load.
 */
public final class ChunkRemoveMetrics {

    private static final long REPORT_WINDOW_MS = 60_000L;

    private static final AtomicLong totalNanos = new AtomicLong();
    private static final AtomicLong callCount = new AtomicLong();
    private static final AtomicLong tickCount = new AtomicLong();
    private static volatile long windowStartMs = System.currentTimeMillis();

    static {
        Thread reporter = new Thread(ChunkRemoveMetrics::reporterLoop, "StormChunkRemoveMetrics");
        reporter.setDaemon(true);
        reporter.start();
    }

    private ChunkRemoveMetrics() {}

    /**
     * Called from advice on the server main thread for every {@code IsoChunk.removeFromWorld()}.
     */
    public static void recordRemoveNanos(long nanos) {
        totalNanos.addAndGet(nanos);
        callCount.incrementAndGet();
    }

    /** Called from advice on {@code MovingObjectUpdateScheduler.startFrame()} per server tick. */
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
                StormLogger.LOGGER.warn("ChunkRemoveMetrics reporter failed", t);
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
                    "ChunkRemoveMetrics: window={}ms ticks=0 calls=0 (no activity)", windowMs);
            return;
        }

        double totalMs = nanos / 1_000_000.0;
        double avgPerTickMs = ticks == 0L ? 0.0 : totalMs / ticks;
        double avgPerCallUs = calls == 0L ? 0.0 : nanos / 1_000.0 / calls;
        double avgCallsPerTick = ticks == 0L ? 0.0 : (double) calls / ticks;

        StormLogger.LOGGER.info(
                "ChunkRemoveMetrics: window={}ms ticks={} calls={} totalMs={} avgPerTickMs={}"
                        + " avgPerCallUs={} avgCallsPerTick={}",
                windowMs,
                ticks,
                calls,
                String.format("%.2f", totalMs),
                String.format("%.3f", avgPerTickMs),
                String.format("%.2f", avgPerCallUs),
                String.format("%.1f", avgCallsPerTick));
    }
}
