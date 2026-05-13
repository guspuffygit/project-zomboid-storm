package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;

/**
 * Passive volume counters for {@code zombie.util.io.BitHeader}. Counts how many times {@code
 * getHeader} fires (demand) and how many times each inner-class {@code release()} fires (returns to
 * pool), broken down by {@code HeaderSize}.
 *
 * <p>Counters are kept by ordinal of {@code HeaderSize} ({@code 0=Byte, 1=Short, 2=Integer,
 * 3=Long}) so the metrics class does not need to import the game enum directly.
 */
public final class BitHeaderMetrics {

    private static final Counter POOL_OPS =
            Counter.builder()
                    .name("pz_bit_header_pool_ops_total")
                    .help("BitHeader pool operations by size and op.")
                    .labelNames("size", "op")
                    .register(StormPrometheus.registry());

    private static final String[] SIZES = {"byte", "short", "integer", "long"};

    static {
        ThreadAllocBytesMetrics.ensureStarted();
    }

    private BitHeaderMetrics() {}

    public static void observeGetHeader(int sizeOrdinal) {
        if (sizeOrdinal >= 0 && sizeOrdinal < SIZES.length) {
            POOL_OPS.labelValues(SIZES[sizeOrdinal], "get").inc();
        }
    }

    public static void observeReleaseByte() {
        POOL_OPS.labelValues("byte", "release").inc();
    }

    public static void observeReleaseShort() {
        POOL_OPS.labelValues("short", "release").inc();
    }

    public static void observeReleaseInteger() {
        POOL_OPS.labelValues("integer", "release").inc();
    }

    public static void observeReleaseLong() {
        POOL_OPS.labelValues("long", "release").inc();
    }
}
