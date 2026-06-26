package io.pzstorm.storm.metrics;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.pzstorm.storm.connection.PeerSendBufferKickConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.znet.ZNetStatistics;
import zombie.network.GameServer;

/**
 * Per-peer RakNet send/resend buffer telemetry and the auto-kick watchdog, polled every server tick
 * from {@link io.pzstorm.storm.advice.servertick.ServerTickAdvice}.
 *
 * <p>Vanilla PZ exports the same buffer/congestion numbers but {@code
 * zombie.network.statistics.data.NetworkStatistic} sums them across all peers into a single
 * aggregate ({@code network{parameter="bytes-in-send-buffer-high"}}). That hides the case we
 * actually need to diagnose: <em>which</em> connection is filling the buffer during a
 * chunk-transfer / connect storm. These gauges expose the same per-priority breakdown but labelled
 * by username so the offender pops out immediately.
 *
 * <p>A peer that disappears between ticks has every label series with its username explicitly set
 * to {@code 0} on the tick the username disappears — the abrupt drop is the signal we use to
 * identify the disconnect-purge that collapses the aggregate.
 *
 * <ul>
 *   <li>{@code storm_peer_send_buffer_bytes{username, priority}} — pending outbound bytes per peer
 *       in RakNet's send queue, broken out by priority (high / medium / low / immediate).
 *   <li>{@code storm_peer_resend_buffer_bytes{username}} — reliable packets awaiting ACK
 *       retransmission for this peer; growth here precedes a timeout-driven disconnect.
 *   <li>{@code storm_peer_packetloss_last_second{username}} — fraction of packets lost last second.
 *   <li>{@code storm_peer_average_ping_ms{username}} — running-average RTT to the peer.
 *   <li>{@code storm_peer_congestion_limited{username}} — {@code 1} when RakNet's congestion
 *       control is currently throttling outbound BPS for this peer, {@code 0} otherwise.
 *   <li>{@code storm_peer_bps_limit_congestion{username}} — current BPS ceiling RakNet's congestion
 *       control has imposed on outbound to this peer (bytes/second).
 *   <li>{@code storm_peer_kicked_send_buffer_total} — counter incremented every time the watchdog
 *       force-disconnects a peer for sustained send-buffer overflow.
 * </ul>
 *
 * <p><b>Watchdog.</b> When {@link PeerSendBufferKickConfig#enabled()} and a peer's {@code
 * bytesInSendBufferHigh} stays above {@link PeerSendBufferKickConfig#thresholdBytes()} for {@link
 * PeerSendBufferKickConfig#holdTicks()} consecutive ticks, that peer is force-disconnected with
 * reason {@link #KICK_REASON}. Disconnects are deferred until after the iteration finishes because
 * {@code UdpEngine.forceDisconnect} mutates {@code udpEngine.connections} (calls {@code
 * removeConnection}) — kicking mid-iteration would skip the next peer in the list.
 */
public final class StormConnectionMetrics {

    public static final String KICK_REASON = "storm-send-buffer-overflow";

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

    private static final Gauge CONGESTION_LIMITED =
            Gauge.builder()
                    .name("storm_peer_congestion_limited")
                    .help(
                            "1 when RakNet's congestion control is currently throttling outbound"
                                    + " BPS for this peer, 0 otherwise. When 1 alongside a growing"
                                    + " storm_peer_send_buffer_bytes{priority=\"high\"}, the peer's"
                                    + " link is saturated and packets are piling up in the pre-wire"
                                    + " queue faster than they can be sent.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Gauge BPS_LIMIT_CONGESTION =
            Gauge.builder()
                    .name("storm_peer_bps_limit_congestion")
                    .help(
                            "Current outbound BPS ceiling RakNet's congestion control has imposed"
                                    + " for this peer (bytes/second). Drops toward zero as packet"
                                    + " loss increases.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Counter KICKED_SEND_BUFFER =
            Counter.builder()
                    .name("storm_peer_kicked_send_buffer_total")
                    .help(
                            "Peers force-disconnected by the Storm send-buffer watchdog for"
                                    + " staying above Storm.PeerSendBufferKickMb for"
                                    + " StormConnectionMetrics.KICK_HOLD_TICKS consecutive ticks."
                                    + " Unlabelled to avoid label-cardinality growth; the specific"
                                    + " username is logged at INFO with the kick.")
                    .register(StormPrometheus.registry());

    private static final Set<String> lastSeenUsernames = new HashSet<>();
    private static final Map<String, Integer> consecutiveTicksOverThreshold = new HashMap<>();

    private StormConnectionMetrics() {}

    /**
     * Iterate {@link GameServer#udpEngine} connections, update every per-peer gauge, and
     * force-disconnect any peer whose HIGH send buffer has been above the watchdog threshold for
     * {@link #KICK_HOLD_TICKS} consecutive ticks.
     *
     * <p>Called from the server tick (single-threaded, main-thread iteration of the connections
     * list). Any peer that was present last tick but is absent now has its label series set to
     * {@code 0} so the disconnect shows up as a visible drop in the time series, and its
     * over-threshold counter is reset.
     *
     * <p>Kicks are deferred to a separate pass after iteration finishes because {@code
     * UdpEngine.forceDisconnect} mutates the same {@code connections} list we are iterating.
     */
    public static void recordAll() {
        UdpEngine engine = GameServer.udpEngine;
        if (engine == null) {
            return;
        }

        List<UdpConnection> connections = engine.connections;
        Set<String> currentUsernames = new HashSet<>(connections.size() * 2);
        List<UdpConnection> toKick = null;

        long kickThresholdBytes = PeerSendBufferKickConfig.thresholdBytes();
        boolean watchdogEnabled = kickThresholdBytes > 0L;

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
            CONGESTION_LIMITED
                    .labelValues(username)
                    .set(stats.isLimitedByCongestionControl ? 1 : 0);
            BPS_LIMIT_CONGESTION.labelValues(username).set(stats.bpsLimitByCongestionControl);

            if (watchdogEnabled && stats.bytesInSendBufferHigh > kickThresholdBytes) {
                int count = consecutiveTicksOverThreshold.getOrDefault(username, 0) + 1;
                consecutiveTicksOverThreshold.put(username, count);
                if (count >= PeerSendBufferKickConfig.holdTicks()) {
                    if (toKick == null) {
                        toKick = new ArrayList<>(2);
                    }
                    toKick.add(c);
                    consecutiveTicksOverThreshold.remove(username);
                }
            } else {
                consecutiveTicksOverThreshold.remove(username);
            }
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
            CONGESTION_LIMITED.labelValues(prev).set(0.0);
            BPS_LIMIT_CONGESTION.labelValues(prev).set(0.0);
            consecutiveTicksOverThreshold.remove(prev);
        }
        lastSeenUsernames.clear();
        lastSeenUsernames.addAll(currentUsernames);

        if (toKick != null) {
            for (UdpConnection c : toKick) {
                String username = labelFor(c);
                double mb = 0.0;
                ZNetStatistics stats = c.getStatistics();
                if (stats != null) {
                    mb = stats.bytesInSendBufferHigh / (1024.0 * 1024.0);
                }
                LOGGER.info(
                        "Storm: force-disconnecting peer {} (steamId={} ip={}) — HIGH send buffer"
                                + " {} MB held above Storm.PeerSendBufferKickMb threshold for {}"
                                + " consecutive ticks",
                        username,
                        c.getSteamId(),
                        c.getIP(),
                        String.format("%.1f", mb),
                        PeerSendBufferKickConfig.holdTicks());
                KICKED_SEND_BUFFER.inc();
                try {
                    c.forceDisconnect(KICK_REASON);
                } catch (Throwable t) {
                    LOGGER.warn("Storm: forceDisconnect failed for peer {}", username, t);
                }
            }
        }
    }

    private static String labelFor(UdpConnection c) {
        String name = c.getUserName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return "guid:" + c.getConnectedGUID();
    }
}
