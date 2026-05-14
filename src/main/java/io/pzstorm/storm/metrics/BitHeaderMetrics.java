package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.metrics.Counter;

/**
 * Passive volume counters for {@code zombie.util.io.BitHeader}. Counts how many times {@code
 * getHeader} fires (demand) and how many times each inner-class {@code release()} fires (returns to
 * pool), broken down by {@code HeaderSize}.
 *
 * <p>Counters are kept by ordinal of {@code HeaderSize} ({@code 0=Byte, 1=Short, 2=Integer,
 * 3=Long}) so the metrics class does not need to import the game enum directly.
 *
 * <p>DataPoints are pre-resolved to {@code static final} fields so each record call collapses to a
 * pure atomic {@code inc()} with no {@code Arrays.asList(...)} allocation in {@code
 * StatefulMetric.labelValues}.
 */
public final class BitHeaderMetrics {

    private static final Counter POOL_OPS =
            Counter.builder()
                    .name("pz_bit_header_pool_ops_total")
                    .help("BitHeader pool operations by size and op.")
                    .labelNames("size", "op")
                    .register(StormPrometheus.registry());

    private static final CounterDataPoint GET_BYTE = POOL_OPS.labelValues("byte", "get");
    private static final CounterDataPoint GET_SHORT = POOL_OPS.labelValues("short", "get");
    private static final CounterDataPoint GET_INTEGER = POOL_OPS.labelValues("integer", "get");
    private static final CounterDataPoint GET_LONG = POOL_OPS.labelValues("long", "get");

    private static final CounterDataPoint RELEASE_BYTE = POOL_OPS.labelValues("byte", "release");
    private static final CounterDataPoint RELEASE_SHORT = POOL_OPS.labelValues("short", "release");
    private static final CounterDataPoint RELEASE_INTEGER =
            POOL_OPS.labelValues("integer", "release");
    private static final CounterDataPoint RELEASE_LONG = POOL_OPS.labelValues("long", "release");

    static {
        ThreadAllocBytesMetrics.ensureStarted();
    }

    private BitHeaderMetrics() {}

    public static void observeGetHeader(int sizeOrdinal) {
        switch (sizeOrdinal) {
            case 0:
                GET_BYTE.inc();
                break;
            case 1:
                GET_SHORT.inc();
                break;
            case 2:
                GET_INTEGER.inc();
                break;
            case 3:
                GET_LONG.inc();
                break;
            default:
                break;
        }
    }

    public static void observeReleaseByte() {
        RELEASE_BYTE.inc();
    }

    public static void observeReleaseShort() {
        RELEASE_SHORT.inc();
    }

    public static void observeReleaseInteger() {
        RELEASE_INTEGER.inc();
    }

    public static void observeReleaseLong() {
        RELEASE_LONG.inc();
    }
}
