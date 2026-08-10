package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;

/**
 * Metrics for {@code GameServer} net-data advice timing.
 *
 * <p>{@link #CALL_DURATION} is a native histogram — buckets grow dynamically as observations land,
 * so no upper-bound choice is baked in. Requires a Prometheus server with native histograms
 * enabled.
 *
 * <p>{@link #DROPPED_TOTAL} counts every {@code mainLoopDealWithNetData} invocation short-circuited
 * by {@link io.pzstorm.storm.advice.netdatadraincap.MainLoopDrainCapAdvice} because its per-spin
 * budget was exceeded. The packet is gone for good: already dequeued and ACKed by RakNet, never
 * processed, discarded back to the pool. A non-zero rate during a reconnect storm confirms the cap
 * is engaging; a sustained non-zero rate under steady-state load indicates the cap (the {@code
 * Storm.NetDataCapMs} sandbox option) is too tight.
 *
 * <p>{@link #DEFERRED_TOTAL_DEPRECATED} publishes the identical count under the original {@code
 * pz_netdata_deferred_total} name — a misnomer (nothing is deferred; the packet is dropped) kept
 * only so existing dashboards and alerts keep working. New queries must use {@code
 * pz_netdata_dropped_total}.
 */
public final class NetDataMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_netdata_call_duration_seconds")
                    .help("Duration of GameServer NetData advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter DROPPED_TOTAL =
            Counter.builder()
                    .name("pz_netdata_dropped_total")
                    .help(
                            "Number of inbound packets dropped because the per-spin drain cap"
                                    + " (Storm.NetDataCapMs sandbox option) was exceeded. Dropped"
                                    + " for good: the packet was already dequeued and ACKed by"
                                    + " RakNet, is never processed, and is discarded back to the"
                                    + " pool.")
                    .register(StormPrometheus.registry());

    /**
     * @deprecated Misnomer — nothing is deferred; the packet is dropped. Kept publishing the same
     *     count as {@link #DROPPED_TOTAL} for dashboard/alert backwards compatibility. Use {@code
     *     pz_netdata_dropped_total}.
     */
    @Deprecated
    private static final Counter DEFERRED_TOTAL_DEPRECATED =
            Counter.builder()
                    .name("pz_netdata_deferred_total")
                    .help(
                            "DEPRECATED: renamed to pz_netdata_dropped_total (these packets are"
                                    + " dropped, not deferred). Publishes the identical count for"
                                    + " backwards compatibility; migrate dashboards and alerts to"
                                    + " the new name.")
                    .register(StormPrometheus.registry());

    private static final Counter VEHICLE_REQUEST_EXEMPT_TOTAL =
            Counter.builder()
                    .name("pz_netdata_vehicle_request_exempt_total")
                    .help(
                            "Number of VehicleRequest packets processed despite the engaged"
                                    + " net-data drain cap. VehicleRequest is the only inbound path"
                                    + " that produces a VehicleFullUpdate for a client missing a"
                                    + " vehicle; dropping it strands invisible cars.")
                    .register(StormPrometheus.registry());

    private static final Counter PREJOIN_EXEMPT_TOTAL =
            Counter.builder()
                    .name("pz_netdata_prejoin_exempt_total")
                    .help(
                            "Number of packets processed despite the engaged net-data drain cap"
                                    + " because their connection had not completed the join"
                                    + " handshake (UdpConnection.isFullyConnected() false). The"
                                    + " login funnel is one-shot and never retried by the vanilla"
                                    + " client, so dropping any of it silently strands the join"
                                    + " until the stalled-connection reaper kills the client."
                                    + " Labelled by packet type.")
                    .labelNames("type")
                    .register(StormPrometheus.registry());

    private static final Counter TYPE_EXEMPT_TOTAL =
            Counter.builder()
                    .name("pz_netdata_type_exempt_total")
                    .help(
                            "Number of packets processed despite the engaged net-data drain cap"
                                    + " because their type is on the one-shot allowlist"
                                    + " (CreatePlayer, ConnectCoop, TimeSync, RequestData,"
                                    + " NetTimedAction, BuildAction, FishingAction): the vanilla"
                                    + " client sends these exactly once with no retry and no"
                                    + " periodic stream regenerates the state, so a single drop"
                                    + " wedges the player (respawn stuck forever, server clock 0"
                                    + " all session, action queue frozen >= 30 min). Labelled by"
                                    + " packet type.")
                    .labelNames("type")
                    .register(StormPrometheus.registry());

    private NetDataMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }

    public static void recordDropped() {
        DROPPED_TOTAL.inc();
        DEFERRED_TOTAL_DEPRECATED.inc();
    }

    public static void recordVehicleRequestExempt() {
        VEHICLE_REQUEST_EXEMPT_TOTAL.inc();
    }

    public static void recordPreJoinExempt(String packetType) {
        PREJOIN_EXEMPT_TOTAL.labelValues(packetType).inc();
    }

    public static void recordTypeExempt(String packetType) {
        TYPE_EXEMPT_TOTAL.labelValues(packetType).inc();
    }
}
