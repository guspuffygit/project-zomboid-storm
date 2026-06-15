package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Histogram;

public final class ZipBackupOnPeriodMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_zip_backup_on_period_call_duration_seconds")
                    .help("Duration of ZipBackupOnPeriod advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private ZipBackupOnPeriodMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
