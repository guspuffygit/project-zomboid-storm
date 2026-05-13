package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.pzstorm.storm.logging.StormLogger;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-thread heap-allocation counters exposed to Prometheus. Uses {@code com.sun.management
 * .ThreadMXBean.getThreadAllocatedBytes(long)} to read the cumulative bytes allocated by each
 * thread of interest, emitted as a Prometheus counter with one series per tracked thread name.
 *
 * <p>Tracked named threads correspond to the hot threads identified in the JFR analysis: the main
 * tick thread and the chunk / LOS / vehicle / network worker threads. Threads named with a {@code
 * PlayerDownloadServer} prefix are aggregated into a single {@code thread="player_download"} series
 * (their count is not fixed).
 *
 * <p>The {@link CounterWithCallback} fires at scrape time, so there's no background daemon — the
 * callback enumerates live threads and reads each cumulative byte count on demand. PromQL {@code
 * rate()} downstream derives bytes/sec.
 *
 * <p>Loaded indirectly via {@link BitHeaderMetrics}'s static initializer (which calls {@link
 * #ensureStarted()}), so the callback registers the moment the first BitHeader patch fires.
 */
public final class ThreadAllocBytesMetrics {

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
    private static final String PLAYER_DL_LABEL = "player_download";

    private static final com.sun.management.ThreadMXBean BEAN = initBean();

    private static final CounterWithCallback ALLOCATED =
            CounterWithCallback.builder()
                    .name("pz_thread_allocated_bytes_total")
                    .help("Cumulative bytes allocated by tracked PZ threads.")
                    .labelNames("thread")
                    .callback(ThreadAllocBytesMetrics::emitSamples)
                    .register(StormPrometheus.registry());

    private ThreadAllocBytesMetrics() {}

    /** No-op; calling forces class load so the static initializer fires. */
    public static void ensureStarted() {}

    private static com.sun.management.ThreadMXBean initBean() {
        try {
            java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
            if (!(raw instanceof com.sun.management.ThreadMXBean)) {
                StormLogger.LOGGER.warn(
                        "ThreadAllocBytesMetrics: ThreadMXBean is not a"
                                + " com.sun.management.ThreadMXBean — disabling");
                return null;
            }
            com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) raw;
            if (!bean.isThreadAllocatedMemorySupported()) {
                StormLogger.LOGGER.warn(
                        "ThreadAllocBytesMetrics: thread-allocated memory not supported on this"
                                + " JVM — disabling");
                return null;
            }
            bean.setThreadAllocatedMemoryEnabled(true);
            return bean;
        } catch (Throwable t) {
            StormLogger.LOGGER.warn("ThreadAllocBytesMetrics: setup failed — disabling", t);
            return null;
        }
    }

    private static void emitSamples(CounterWithCallback.Callback callback) {
        if (BEAN == null) {
            // Bean unavailable — keep series stable by emitting zeros.
            for (String tracked : TRACKED) {
                callback.call(0.0, tracked);
            }
            callback.call(0.0, PLAYER_DL_LABEL);
            return;
        }

        // Single bulk enumeration per scrape: one getAllThreadIds + one getThreadInfo call,
        // then at most ~150 getThreadAllocatedBytes lookups. Hash map sized for the tracked
        // set so the per-name match is O(1).
        long[] tids = BEAN.getAllThreadIds();
        ThreadInfo[] infos = BEAN.getThreadInfo(tids);

        Map<String, Long> trackedBytes = new HashMap<>(TRACKED.length * 2);
        long playerDlBytes = 0L;

        for (int i = 0; i < tids.length; i++) {
            ThreadInfo info = infos[i];
            if (info == null) {
                continue;
            }
            String name = info.getThreadName();

            // Resolve the tracked-name match before doing the (slightly pricier) bean lookup,
            // so we skip the per-thread allocation read entirely for threads we don't care about.
            boolean isPlayerDl = name.startsWith(PLAYER_DL_PREFIX);
            String trackedHit = null;
            if (!isPlayerDl) {
                for (String tracked : TRACKED) {
                    if (tracked.equals(name)) {
                        trackedHit = tracked;
                        break;
                    }
                }
                if (trackedHit == null) {
                    continue;
                }
            }

            long bytes = BEAN.getThreadAllocatedBytes(tids[i]);
            if (bytes < 0L) {
                continue;
            }
            if (isPlayerDl) {
                playerDlBytes += bytes;
            } else {
                // If multiple live threads share a tracked name (shouldn't happen for the
                // names we track, but be defensive), sum them.
                trackedBytes.merge(trackedHit, bytes, Long::sum);
            }
        }

        // Emit one sample per tracked name (zero if no live thread), so the series stay stable
        // across scrapes even when a worker thread is briefly absent.
        for (String tracked : TRACKED) {
            Long v = trackedBytes.get(tracked);
            callback.call(v == null ? 0.0 : v.doubleValue(), tracked);
        }
        callback.call((double) playerDlBytes, PLAYER_DL_LABEL);
    }
}
