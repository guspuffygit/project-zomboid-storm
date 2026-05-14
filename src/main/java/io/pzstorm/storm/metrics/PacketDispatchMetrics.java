package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.datapoints.DistributionDataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PacketDispatchMetrics {

    private static final Counter DISPATCH =
            Counter.builder()
                    .name("storm_packet_dispatch_total")
                    .help("Packet dispatch invocations.")
                    .labelNames("packet")
                    .register(StormPrometheus.registry());

    private static final Histogram HANDLER_DURATION =
            Histogram.builder()
                    .name("storm_packet_handler_duration_seconds")
                    .help("Duration of packet handler dispatch loop.")
                    .labelNames("packet")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter TYPED_EVENT =
            Counter.builder()
                    .name("storm_packet_typed_event_total")
                    .help("Typed packet event resolution outcomes.")
                    .labelNames("packet", "result")
                    .register(StormPrometheus.registry());

    private static final Map<String, CounterDataPoint> DISPATCH_DP = new ConcurrentHashMap<>();
    private static final Map<String, DistributionDataPoint> DURATION_DP = new ConcurrentHashMap<>();
    private static final Map<String, CounterDataPoint> TYPED_EVENT_DP = new ConcurrentHashMap<>();

    private PacketDispatchMetrics() {}

    public static void recordDispatch(String packet) {
        DISPATCH_DP.computeIfAbsent(packet, p -> DISPATCH.labelValues(p)).inc();
    }

    public static void recordHandlerNanos(String packet, long nanos) {
        DURATION_DP
                .computeIfAbsent(packet, p -> HANDLER_DURATION.labelValues(p))
                .observe(nanos / 1e9);
    }

    public static void recordTypedEvent(String packet, String result) {
        String key = packet + ":" + result;
        TYPED_EVENT_DP.computeIfAbsent(key, k -> TYPED_EVENT.labelValues(packet, result)).inc();
    }
}
