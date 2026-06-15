package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class FileSystemUpdateAsyncTransactionsMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_file_system_update_async_transactions_call_duration_seconds")
                    .help("Duration of FileSystemUpdateAsyncTransactions advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private FileSystemUpdateAsyncTransactionsMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
