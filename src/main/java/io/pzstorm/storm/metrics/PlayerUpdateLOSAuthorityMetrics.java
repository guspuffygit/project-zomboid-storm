package io.pzstorm.storm.metrics;

import io.pzstorm.storm.logging.StormLogger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 4 substitution counters for {@code IsoPlayer.updateLOS}. Tracks how often each {@code
 * IsoPlayerUpdateLOSAuthorityAdvice} decision wins per 60-second window so we can confirm the gate
 * is firing for the population we expect (solo + fresh report) and quantify fallback rates while
 * validating the rollout.
 *
 * <p><b>Thread contract:</b> recorder methods are called from the GameServer main update thread
 * (from the {@code updateLOS} advice and {@link io.pzstorm.storm.los.PlayerLOSReportApplier}). The
 * static {@code StormPlayerUpdateLOSAuthorityMetrics} daemon reports from its own thread, but it
 * only touches the {@link AtomicLong} counters and the {@code volatile windowStartMs} — it does not
 * read or mutate any state in {@link io.pzstorm.storm.los.PlayerLOSAuthorityManager} or {@link
 * io.pzstorm.storm.los.PlayerLOSReportCache}.
 */
public final class PlayerUpdateLOSAuthorityMetrics {

    private static final long REPORT_WINDOW_MS = 60_000L;

    private static final AtomicLong takenAuthority = new AtomicLong();
    private static final AtomicLong fellBackGrouped = new AtomicLong();
    private static final AtomicLong fellBackNoReport = new AtomicLong();
    private static final AtomicLong fellBackTruncated = new AtomicLong();
    private static final AtomicLong resolveFailures = new AtomicLong();
    private static volatile long windowStartMs = System.currentTimeMillis();

    static {
        Thread reporter =
                new Thread(
                        PlayerUpdateLOSAuthorityMetrics::reporterLoop,
                        "StormPlayerUpdateLOSAuthorityMetrics");
        reporter.setDaemon(true);
        reporter.start();
    }

    private PlayerUpdateLOSAuthorityMetrics() {}

    public static void recordTakenAuthority() {
        takenAuthority.incrementAndGet();
    }

    public static void recordFellBackGrouped() {
        fellBackGrouped.incrementAndGet();
    }

    public static void recordFellBackNoReport() {
        fellBackNoReport.incrementAndGet();
    }

    public static void recordFellBackTruncated() {
        fellBackTruncated.incrementAndGet();
    }

    public static void recordResolveFailures(int count) {
        resolveFailures.addAndGet(count);
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
                StormLogger.LOGGER.warn("PlayerUpdateLOSAuthorityMetrics reporter failed", t);
            }
        }
    }

    private static void report() {
        long taken = takenAuthority.getAndSet(0L);
        long grouped = fellBackGrouped.getAndSet(0L);
        long noReport = fellBackNoReport.getAndSet(0L);
        long truncated = fellBackTruncated.getAndSet(0L);
        long failures = resolveFailures.getAndSet(0L);
        long now = System.currentTimeMillis();
        long windowMs = now - windowStartMs;
        windowStartMs = now;

        long fallbacks = grouped + noReport + truncated;
        if (taken == 0L && fallbacks == 0L) {
            StormLogger.LOGGER.info(
                    "PlayerUpdateLOSAuthorityMetrics: window={}ms taken=0 fallbacks=0 (no activity)",
                    windowMs);
            return;
        }

        StormLogger.LOGGER.info(
                "PlayerUpdateLOSAuthorityMetrics: window={}ms taken={} fellBack={{grouped={},"
                        + " noReport={}, truncated={}}} resolveFailures={}",
                windowMs,
                taken,
                grouped,
                noReport,
                truncated,
                failures);
    }
}
