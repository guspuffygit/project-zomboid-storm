package io.pzstorm.storm.metrics;

import io.pzstorm.storm.logging.StormLogger;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-thread heap-allocation rate sampler. Uses {@code com.sun.management.ThreadMXBean
 * .getThreadAllocatedBytes(long)} to read the cumulative bytes allocated by each thread of
 * interest, and reports per-thread bytes/sec averaged over a 60s window via {@link
 * StormLogger#LOGGER}.
 *
 * <p>Tracked named threads correspond to the hot threads identified in the JFR analysis: the main
 * tick thread and the chunk / LOS / vehicle / network worker threads. Threads named with a {@code
 * PlayerDownloadServer} prefix are aggregated (their count is not fixed).
 *
 * <p>Numbers reported here are <em>measured</em> rather than extrapolated, so the deltas before and
 * after a fix are directly comparable. Continuous logging means we don't need to time a JFR
 * recording to see the change.
 *
 * <p>Loaded indirectly via {@link BitHeaderMetrics}'s static initializer (which calls {@link
 * #ensureStarted()}), so the daemon starts the moment the first BitHeader patch fires.
 */
public final class ThreadAllocBytesMetrics {

    private static final long REPORT_WINDOW_MS = 60_000L;

    private static final String[] TRACKED = {
        "main",
        "SaveChunk",
        "LoadChunk",
        "LOS",
        "RecalcAll",
        "WorldReuser",
        "UdpEngine",
        "ServerPlayersVehicles",
        "IsoRegionWorker"
    };

    private static final String PLAYER_DL_PREFIX = "PlayerDownloadServer";

    private static final Map<Long, Long> lastBytesByTid = new HashMap<>();
    private static volatile long lastTotalBytes = -1L;
    private static volatile long windowStartMs = System.currentTimeMillis();

    static {
        Thread reporter =
                new Thread(ThreadAllocBytesMetrics::reporterLoop, "StormThreadAllocBytesMetrics");
        reporter.setDaemon(true);
        reporter.start();
    }

    private ThreadAllocBytesMetrics() {}

    /** No-op; calling forces class load so the static initializer fires. */
    public static void ensureStarted() {}

    private static void reporterLoop() {
        com.sun.management.ThreadMXBean bean;
        try {
            java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
            if (!(raw instanceof com.sun.management.ThreadMXBean)) {
                StormLogger.LOGGER.warn(
                        "ThreadAllocBytesMetrics: ThreadMXBean is not a"
                                + " com.sun.management.ThreadMXBean — disabling");
                return;
            }
            bean = (com.sun.management.ThreadMXBean) raw;
            if (!bean.isThreadAllocatedMemorySupported()) {
                StormLogger.LOGGER.warn(
                        "ThreadAllocBytesMetrics: thread-allocated memory not supported on this"
                                + " JVM — disabling");
                return;
            }
            bean.setThreadAllocatedMemoryEnabled(true);
        } catch (Throwable t) {
            StormLogger.LOGGER.warn("ThreadAllocBytesMetrics: setup failed — disabling", t);
            return;
        }

        while (true) {
            try {
                Thread.sleep(REPORT_WINDOW_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                report(bean);
            } catch (Throwable t) {
                StormLogger.LOGGER.warn("ThreadAllocBytesMetrics report failed", t);
            }
        }
    }

    private static void report(com.sun.management.ThreadMXBean bean) {
        long now = System.currentTimeMillis();
        long windowMs = now - windowStartMs;
        windowStartMs = now;

        long[] tids = bean.getAllThreadIds();
        ThreadInfo[] infos = bean.getThreadInfo(tids);

        Map<Long, Long> currentByTid = new HashMap<>(tids.length * 2);
        Map<String, Long> currentByName = new HashMap<>();
        long currentTotal = 0L;
        long playerDlCurrent = 0L;
        long playerDlPrev = 0L;
        int playerDlCount = 0;

        for (int i = 0; i < tids.length; i++) {
            ThreadInfo info = infos[i];
            if (info == null) {
                continue;
            }
            long tid = tids[i];
            long bytes = bean.getThreadAllocatedBytes(tid);
            if (bytes < 0L) {
                continue;
            }
            currentTotal += bytes;
            currentByTid.put(tid, bytes);

            String name = info.getThreadName();
            if (name.startsWith(PLAYER_DL_PREFIX)) {
                playerDlCurrent += bytes;
                Long prev = lastBytesByTid.get(tid);
                if (prev != null) {
                    playerDlPrev += prev;
                }
                playerDlCount++;
                continue;
            }

            for (String tracked : TRACKED) {
                if (tracked.equals(name)) {
                    currentByName.put(name, bytes);
                    break;
                }
            }
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("ThreadAllocBytesMetrics: window=").append(windowMs).append("ms");

        long totalDelta = (lastTotalBytes < 0L) ? -1L : (currentTotal - lastTotalBytes);
        sb.append(" total=").append(formatRate(totalDelta, windowMs));

        for (String tracked : TRACKED) {
            Long cur = currentByName.get(tracked);
            if (cur == null) {
                continue;
            }
            // Find prev by tid: walk back through previous map looking for any tid whose name
            // matched this tracked name. We don't have a reverse map, so we recompute from the
            // existing snapshot — fine for ~150 threads.
            Long prev = findPrevForName(infos, tids, tracked);
            long delta = (prev == null) ? -1L : (cur - prev);
            sb.append(" ").append(tracked).append("=").append(formatRate(delta, windowMs));
        }

        sb.append(" PlayerDownloadServer*N=").append(playerDlCount).append("=");
        long playerDlDelta = (playerDlCount == 0) ? -1L : (playerDlCurrent - playerDlPrev);
        sb.append(formatRate(playerDlDelta, windowMs));

        StormLogger.LOGGER.info(sb.toString());

        lastTotalBytes = currentTotal;
        lastBytesByTid.clear();
        lastBytesByTid.putAll(currentByTid);
    }

    private static Long findPrevForName(ThreadInfo[] infos, long[] tids, String name) {
        for (int i = 0; i < tids.length; i++) {
            ThreadInfo info = infos[i];
            if (info == null) {
                continue;
            }
            if (name.equals(info.getThreadName())) {
                return lastBytesByTid.get(tids[i]);
            }
        }
        return null;
    }

    private static String formatRate(long deltaBytes, long windowMs) {
        if (deltaBytes < 0L) {
            return "n/a";
        }
        long perSec = (windowMs <= 0L) ? 0L : (long) (deltaBytes * 1000.0 / windowMs);
        return formatBytes(perSec) + "/s";
    }

    private static String formatBytes(long b) {
        if (b >= 1_000_000_000L) {
            return String.format("%.2fGB", b / 1_000_000_000.0);
        }
        if (b >= 1_000_000L) {
            return String.format("%.2fMB", b / 1_000_000.0);
        }
        if (b >= 1_000L) {
            return String.format("%.2fKB", b / 1_000.0);
        }
        return b + "B";
    }
}
