package io.pzstorm.storm.metrics;

import io.pzstorm.storm.logging.StormLogger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Passive volume counters for {@code zombie.util.io.BitHeader}. Captures one number per minute: how
 * many times {@code getHeader} fired (demand) and how many times each inner-class {@code release()}
 * fired (returns to pool), broken down by {@code HeaderSize}.
 *
 * <p>This class is paired with {@link ThreadAllocBytesMetrics} to provide a baseline before any
 * change to {@code BitHeader}'s pool. The reporter logs once per 60s window via {@link
 * StormLogger#LOGGER} at INFO level.
 *
 * <p>Counters use {@link LongAdder} because the call rate from advice can reach millions per second
 * on the server main thread; {@code AtomicLong} CAS contention would itself become measurable and
 * pollute the very signal we're trying to capture.
 *
 * <p>Counters are kept by ordinal of {@code HeaderSize} ({@code 0=Byte, 1=Short, 2=Integer,
 * 3=Long}) so the metrics class does not need to import the game enum directly.
 */
public final class BitHeaderMetrics {

    private static final long REPORT_WINDOW_MS = 60_000L;

    private static final LongAdder[] getHeaderCalls = {
        new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()
    };
    private static final LongAdder[] releases = {
        new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()
    };

    private static volatile long windowStartMs = System.currentTimeMillis();

    static {
        Thread reporter = new Thread(BitHeaderMetrics::reporterLoop, "StormBitHeaderMetrics");
        reporter.setDaemon(true);
        reporter.start();
        // Force the sibling thread-allocation metrics to load and start its own daemon.
        ThreadAllocBytesMetrics.ensureStarted();
    }

    private BitHeaderMetrics() {}

    public static void observeGetHeader(int sizeOrdinal) {
        if (sizeOrdinal >= 0 && sizeOrdinal < getHeaderCalls.length) {
            getHeaderCalls[sizeOrdinal].increment();
        }
    }

    public static void observeReleaseByte() {
        releases[0].increment();
    }

    public static void observeReleaseShort() {
        releases[1].increment();
    }

    public static void observeReleaseInteger() {
        releases[2].increment();
    }

    public static void observeReleaseLong() {
        releases[3].increment();
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
                StormLogger.LOGGER.warn("BitHeaderMetrics reporter failed", t);
            }
        }
    }

    private static void report() {
        long now = System.currentTimeMillis();
        long windowMs = now - windowStartMs;
        windowStartMs = now;

        long gB = getHeaderCalls[0].sumThenReset();
        long gS = getHeaderCalls[1].sumThenReset();
        long gI = getHeaderCalls[2].sumThenReset();
        long gL = getHeaderCalls[3].sumThenReset();
        long rB = releases[0].sumThenReset();
        long rS = releases[1].sumThenReset();
        long rI = releases[2].sumThenReset();
        long rL = releases[3].sumThenReset();

        long getTotal = gB + gS + gI + gL;
        long relTotal = rB + rS + rI + rL;

        if (getTotal == 0L && relTotal == 0L) {
            StormLogger.LOGGER.info("BitHeaderMetrics: window={}ms (no activity)", windowMs);
            return;
        }

        double secs = Math.max(windowMs, 1L) / 1000.0;

        StormLogger.LOGGER.info(
                "BitHeaderMetrics: window={}ms"
                        + " byte:get={}/rel={}"
                        + " short:get={}/rel={}"
                        + " int:get={}/rel={}"
                        + " long:get={}/rel={}"
                        + " total:get={}({}/s)/rel={}({}/s)",
                windowMs,
                gB,
                rB,
                gS,
                rS,
                gI,
                rI,
                gL,
                rL,
                getTotal,
                String.format("%.0f", getTotal / secs),
                relTotal,
                String.format("%.0f", relTotal / secs));
    }
}
