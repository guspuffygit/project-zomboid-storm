package io.pzstorm.storm.metrics;

import io.pzstorm.storm.logging.StormFileLoggerFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Per-tick wall-clock accumulator for the dedicated server's {@code GameServer.main} loop.
 *
 * <p>Each step (a method call inside the frame-step block) is timed by its own advice and reported
 * here via {@link #record(String, long)}. At the start of every new tick {@link #beginTick()}
 * flushes the accumulated step times of the just-completed tick as a single log line to a dedicated
 * {@code timings.log} file (under {@code <STORM_LOG_DIR>/storm/}) capped at 25&nbsp;MB with one
 * rolled archive. Routing to a private file keeps the per-tick spam out of {@code main.log} /
 * {@code debug.log}.
 *
 * <p>Gated by {@code -Dstorm.mainloop.timings=true}. Off by default; when off, {@link #enabled()}
 * is {@code false} and the recording path is a no-op (the per-step advices short-circuit before
 * reading {@code nanoTime}). Server-only by registration — the patches that feed this are
 * registered under the {@code StormEnv.isStormServer()} gate in {@code StormClassTransformers}.
 */
public final class MainLoopStepTimings {

    private static final boolean ENABLED = Boolean.getBoolean("storm.mainloop.timings");

    private static final Logger TIMINGS_LOG =
            ENABLED
                    ? StormFileLoggerFactory.create(
                            "io.pzstorm.storm.timings",
                            StormFileLoggerFactory.LOG_HOME + "/storm",
                            "timings",
                            "log",
                            25,
                            1,
                            null)
                    : null;

    private static final ConcurrentHashMap<String, AtomicLong> STEP_NANOS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> STEP_CALLS =
            new ConcurrentHashMap<>();

    private static volatile long tickStartNanos = 0L;
    private static volatile long frameStepEndNanos = 0L;
    private static volatile long tickCounter = 0L;

    private MainLoopStepTimings() {}

    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * Called from the advice that runs first in each frame-step (currently {@code
     * ServerMap.preupdate} on enter). Flushes the previous tick (if any) to the log and resets.
     */
    public static void beginTick() {
        if (!ENABLED) {
            return;
        }
        if (tickStartNanos != 0L) {
            flush();
        }
        STEP_NANOS.clear();
        STEP_CALLS.clear();
        tickStartNanos = System.nanoTime();
        frameStepEndNanos = 0L;
        tickCounter++;
    }

    /**
     * Called by {@code WorldMapVisitedServerUpdateAdvice.onExit} — the last instrumented call
     * inside {@code GameServer.main}'s frame-step block. Snapshots the moment frame-step work
     * finishes so {@link #flush()} can split the tick total into in-frame work vs idle wait
     * (rate-limiter sleep + inter-frame net polling).
     */
    public static void markFrameStepEnd() {
        if (!ENABLED) {
            return;
        }
        frameStepEndNanos = System.nanoTime();
    }

    public static void record(String stepName, long nanos) {
        if (!ENABLED) {
            return;
        }
        STEP_NANOS.computeIfAbsent(stepName, k -> new AtomicLong()).addAndGet(nanos);
        STEP_CALLS.computeIfAbsent(stepName, k -> new AtomicLong()).incrementAndGet();
    }

    private static void flush() {
        long flushNanos = System.nanoTime();
        long totalNanos = flushNanos - tickStartNanos;

        List<Map.Entry<String, AtomicLong>> entries = new ArrayList<>(STEP_NANOS.entrySet());
        entries.sort(
                Comparator.comparingLong((Map.Entry<String, AtomicLong> e) -> e.getValue().get())
                        .reversed());

        StringBuilder sb = new StringBuilder(256);
        sb.append("[MainLoopTiming] tick=").append(tickCounter);
        sb.append(" total=").append(fmtMs(totalNanos));
        long frameStepBodyNanos = -1L;
        long idleNanos = -1L;
        if (frameStepEndNanos != 0L && frameStepEndNanos >= tickStartNanos) {
            frameStepBodyNanos = frameStepEndNanos - tickStartNanos;
            idleNanos = flushNanos - frameStepEndNanos;
            sb.append(" frameStep.body=").append(fmtMs(frameStepBodyNanos));
            sb.append(" idle=").append(fmtMs(idleNanos));
        }
        sb.append(" steps=").append(entries.size());
        long measuredNanos = 0L;
        for (Map.Entry<String, AtomicLong> e : entries) {
            long stepNanos = e.getValue().get();
            measuredNanos += stepNanos;
            long calls = STEP_CALLS.getOrDefault(e.getKey(), new AtomicLong()).get();
            sb.append(" | ").append(e.getKey()).append('=').append(fmtMs(stepNanos));
            if (calls > 1L) {
                sb.append('x').append(calls);
            }
        }
        long unmeasuredFrameStepNanos =
                frameStepBodyNanos >= 0L ? frameStepBodyNanos - measuredNanos : -1L;
        if (unmeasuredFrameStepNanos > 0L) {
            sb.append(" | frameStep.other=").append(fmtMs(unmeasuredFrameStepNanos));
        } else {
            long otherNanos = totalNanos - measuredNanos;
            if (otherNanos > 0L) {
                sb.append(" | other=").append(fmtMs(otherNanos));
            }
        }
        TIMINGS_LOG.info(sb.toString());
    }

    private static String fmtMs(long nanos) {
        double ms = nanos / 1_000_000.0;
        if (ms >= 10.0) {
            return String.format("%.1fms", ms);
        }
        return String.format("%.2fms", ms);
    }
}
