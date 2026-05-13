package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

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

    private PacketDispatchMetrics() {}

    public static void recordDispatch(String packet) {
        DISPATCH.labelValues(packet).inc();
    }

    public static void recordHandlerNanos(String packet, long nanos) {
        HANDLER_DURATION.labelValues(packet).observe(nanos / 1e9);
    }

    public static void recordTypedEvent(String packet, String result) {
        TYPED_EVENT.labelValues(packet, result).inc();
    }
}
