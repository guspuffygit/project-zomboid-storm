package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.Gauge;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.znet.ZNetStatistics;
import zombie.network.GameServer;

/**
 * Per-peer RakNet send/resend buffer telemetry, polled every server tick from {@link
 * io.pzstorm.storm.advice.servertick.ServerTickAdvice}.
 *
 * <p>Vanilla PZ exports the same numbers but {@code
 * zombie.network.statistics.data.NetworkStatistic} sums them across all peers into a single
 * aggregate ({@code network{parameter="bytes-in-send-buffer-high"}}). That hides the case we
 * actually need to diagnose: <em>which</em> connection is filling the buffer during a
 * chunk-transfer / connect storm. These gauges expose the same per-priority breakdown but labelled
 * by username so the offender pops out immediately.
 *
 * <p>A peer that disconnects between ticks has its label series explicitly set to {@code 0} on the
 * tick its username disappears — the abrupt drop to zero is exactly the signal we use to identify
 * the disconnect-purge that collapses the aggregate.
 *
 * <ul>
 *   <li>{@code storm_peer_send_buffer_bytes{username, priority}} — pending outbound bytes per peer
 *       in RakNet's send queue, broken out by priority (high / medium / low / immediate).
 *   <li>{@code storm_peer_resend_buffer_bytes{username}} — reliable packets awaiting ACK
 *       retransmission for this peer; growth here precedes a timeout-driven disconnect.
 *   <li>{@code storm_peer_packetloss_last_second{username}} — fraction of packets lost last second.
 *   <li>{@code storm_peer_average_ping_ms{username}} — running-average RTT to the peer.
 * </ul>
 */
public final class StormConnectionMetrics {

    private static final Gauge SEND_BUFFER_BYTES =
            Gauge.builder()
                    .name("storm_peer_send_buffer_bytes")
                    .help(
                            "Pending outbound bytes in RakNet's send queue for one connected peer,"
                                    + " labelled by username and priority. Per-peer breakdown of"
                                    + " the vanilla aggregate network{parameter=\"bytes-in-send-buffer-*\"}."
                                    + " Spikes here identify which client is on the receiving end of"
                                    + " a chunk-transfer / introduction-packet storm.")
                    .labelNames("username", "priority")
                    .register(StormPrometheus.registry());

    private static final Gauge RESEND_BUFFER_BYTES =
            Gauge.builder()
                    .name("storm_peer_resend_buffer_bytes")
                    .help(
                            "Reliable bytes awaiting retransmission for one connected peer (RakNet"
                                    + " resend queue). Sustained growth means the peer's ACKs are not"
                                    + " keeping up and a timeout-driven disconnect is approaching.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Gauge PACKETLOSS_LAST_SECOND =
            Gauge.builder()
                    .name("storm_peer_packetloss_last_second")
                    .help(
                            "Fraction of packets lost to this peer over the last second (RakNet"
                                    + " RakNetStatistics::packetlossLastSecond). 0..1.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Gauge AVERAGE_PING_MS =
            Gauge.builder()
                    .name("storm_peer_average_ping_ms")
                    .help("Running-average round-trip time (milliseconds) to this peer.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Set<String> lastSeenUsernames = new HashSet<>();

    private StormConnectionMetrics() {}

    /**
     * Iterate {@link GameServer#udpEngine} connections and update every per-peer gauge. Safe to
     * call from the server tick (single-threaded, main-thread iteration of the connections list).
     * Any peer that was present last tick but is absent now has its label series set to {@code 0}
     * so the disconnect shows up as a visible drop in the time series.
     */
    public static void recordAll() {
        UdpEngine engine = GameServer.udpEngine;
        if (engine == null) {
            return;
        }

        List<UdpConnection> connections = engine.connections;
        Set<String> currentUsernames = new HashSet<>(connections.size() * 2);

        for (int i = 0; i < connections.size(); i++) {
            UdpConnection c = connections.get(i);
            if (c == null) {
                continue;
            }
            ZNetStatistics stats = c.getStatistics();
            if (stats == null) {
                continue;
            }

            String username = labelFor(c);
            currentUsernames.add(username);

            SEND_BUFFER_BYTES
                    .labelValues(username, "immediate")
                    .set(stats.bytesInSendBufferImmediate);
            SEND_BUFFER_BYTES.labelValues(username, "high").set(stats.bytesInSendBufferHigh);
            SEND_BUFFER_BYTES.labelValues(username, "medium").set(stats.bytesInSendBufferMedium);
            SEND_BUFFER_BYTES.labelValues(username, "low").set(stats.bytesInSendBufferLow);
            RESEND_BUFFER_BYTES.labelValues(username).set(stats.bytesInResendBuffer);
            PACKETLOSS_LAST_SECOND.labelValues(username).set(stats.packetlossLastSecond);
            AVERAGE_PING_MS.labelValues(username).set(c.getAveragePing());
        }

        for (String prev : lastSeenUsernames) {
            if (currentUsernames.contains(prev)) {
                continue;
            }
            SEND_BUFFER_BYTES.labelValues(prev, "immediate").set(0.0);
            SEND_BUFFER_BYTES.labelValues(prev, "high").set(0.0);
            SEND_BUFFER_BYTES.labelValues(prev, "medium").set(0.0);
            SEND_BUFFER_BYTES.labelValues(prev, "low").set(0.0);
            RESEND_BUFFER_BYTES.labelValues(prev).set(0.0);
            PACKETLOSS_LAST_SECOND.labelValues(prev).set(0.0);
            AVERAGE_PING_MS.labelValues(prev).set(0.0);
        }
        lastSeenUsernames.clear();
        lastSeenUsernames.addAll(currentUsernames);
    }

    private static String labelFor(UdpConnection c) {
        String name = c.getUserName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return "guid:" + c.getConnectedGUID();
    }
}
