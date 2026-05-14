package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.prometheus.metrics.core.metrics.Histogram;
import io.pzstorm.storm.transfer.StormTransferHandler;

public final class TransferMetrics {

    private static final Counter REQUESTS =
            Counter.builder()
                    .name("storm_transfer_requests_total")
                    .help("Storm transfer requests by terminal outcome.")
                    .labelNames("outcome")
                    .register(StormPrometheus.registry());

    private static final CounterDataPoint ACCEPTED = REQUESTS.labelValues("accepted");
    private static final CounterDataPoint REJECTED = REQUESTS.labelValues("rejected");
    private static final CounterDataPoint DONE = REQUESTS.labelValues("done");
    private static final CounterDataPoint CANCELLED = REQUESTS.labelValues("cancelled");

    private static final GaugeWithCallback PENDING_SIZE =
            GaugeWithCallback.builder()
                    .name("storm_transfer_pending_size")
                    .help("Current entry count in the Storm transfer pending map.")
                    .callback(cb -> cb.call(StormTransferHandler.pendingSize()))
                    .register(StormPrometheus.registry());

    private static final Histogram SETTLE_DURATION =
            Histogram.builder()
                    .name("storm_transfer_settle_duration_seconds")
                    .help("Wall-clock duration from transfer accept to successful completion.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private TransferMetrics() {}

    public static void recordAccepted() {
        ACCEPTED.inc();
    }

    public static void recordRejected() {
        REJECTED.inc();
    }

    public static void recordDone(long acceptedAtNanos) {
        DONE.inc();
        SETTLE_DURATION.observe((System.nanoTime() - acceptedAtNanos) / 1e9);
    }

    public static void recordCancelled() {
        CANCELLED.inc();
    }
}
