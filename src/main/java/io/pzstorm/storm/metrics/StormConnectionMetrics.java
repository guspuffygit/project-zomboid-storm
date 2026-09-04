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
import org.jetbrains.annotations.Nullable;
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
 *   <li>{@code storm_peer_send_buffer_messages{username, priority}} — the same queue counted in
 *       messages instead of bytes, which is the unit a chunk backlog is actually denominated in.
 *   <li>{@code storm_peer_resend_buffer_messages{username}} — reliable messages awaiting
 *       retransmission.
 *   <li>{@code storm_peer_bandwidth_limited{username}} and {@code
 *       storm_peer_bps_limit_outgoing{username}} — throttling against a configured cap, as opposed
 *       to against congestion.
 *   <li>{@code storm_peer_kicked_send_buffer_total} — counter incremented every time the watchdog
 *       force-disconnects a peer for sustained send-buffer overflow.
 * </ul>
 *
 * <p>Everything above comes from a single {@code UdpConnection.getStatistics()} per peer per tick —
 * the one JNI call, whose {@code ZNetStatistics} snapshot is then read field by field. Do not add a
 * second call to sample more fields; it allocates a fresh snapshot and does ~31 JNI write-backs.
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

    private static final Gauge SEND_BUFFER_MESSAGES =
            Gauge.builder()
                    .name("storm_peer_send_buffer_messages")
                    .help(
                            "Pending outbound RakNet messages for one peer, labelled by username and"
                                    + " priority. The message count is what identifies a chunk backlog:"
                                    + " SentChunkPacket fragments every compressed chunk into"
                                    + " 1000-byte HIGH/RELIABLE messages, so priority=\"high\" counts"
                                    + " chunk fragments sitting between the download worker and the"
                                    + " wire. Bytes alone cannot tell 40 MB of one broadcast from 40000"
                                    + " queued chunk fragments.")
                    .labelNames("username", "priority")
                    .register(StormPrometheus.registry());

    private static final Gauge RESEND_BUFFER_MESSAGES =
            Gauge.builder()
                    .name("storm_peer_resend_buffer_messages")
                    .help(
                            "Reliable messages awaiting retransmission for one peer. Paired with"
                                    + " storm_peer_resend_buffer_bytes this gives mean retransmit size,"
                                    + " which separates many small lost chunk fragments from a few large"
                                    + " lost payloads.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Gauge BANDWIDTH_LIMITED =
            Gauge.builder()
                    .name("storm_peer_bandwidth_limited")
                    .help(
                            "1 when RakNet is throttling this peer against its configured outgoing"
                                    + " bandwidth cap rather than against congestion control, 0"
                                    + " otherwise. Distinct from storm_peer_congestion_limited: this one"
                                    + " is a ceiling somebody configured and can raise, the other is the"
                                    + " link itself backing off.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Gauge BPS_LIMIT_OUTGOING =
            Gauge.builder()
                    .name("storm_peer_bps_limit_outgoing")
                    .help(
                            "The configured outbound bytes/second ceiling RakNet is applying to this"
                                    + " peer. 0 means uncapped.")
                    .labelNames("username")
                    .register(StormPrometheus.registry());

    private static final Counter KICKED_SEND_BUFFER =
            Counter.builder()
                    .name("storm_peer_kicked_send_buffer_total")
                    .help(
                            "Peers force-disconnected by the Storm send-buffer watchdog for"
                                    + " staying above Storm.PeerSendBufferKickMb for"
                                    + " Storm.PeerSendBufferKickHoldTicks consecutive ticks."
                                    + " Unlabelled to avoid label-cardinality growth; the specific"
                                    + " username is logged at INFO with the kick.")
                    .register(StormPrometheus.registry());

    private static final Set<String> lastSeenUsernames = new HashSet<>();
    private static final Map<String, Integer> consecutiveTicksOverThreshold = new HashMap<>();

    private StormConnectionMetrics() {}

    /**
     * Iterate {@link GameServer#udpEngine} connections, update every per-peer gauge, and
     * force-disconnect any peer whose HIGH send buffer has been above the watchdog threshold for
     * {@link PeerSendBufferKickConfig#holdTicks()} consecutive ticks.
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
            if (username == null) {
                continue;
            }
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
            SEND_BUFFER_MESSAGES
                    .labelValues(username, "immediate")
                    .set(stats.messageInSendBufferImmediate);
            SEND_BUFFER_MESSAGES.labelValues(username, "high").set(stats.messageInSendBufferHigh);
            SEND_BUFFER_MESSAGES
                    .labelValues(username, "medium")
                    .set(stats.messageInSendBufferMedium);
            SEND_BUFFER_MESSAGES.labelValues(username, "low").set(stats.messageInSendBufferLow);
            RESEND_BUFFER_MESSAGES.labelValues(username).set(stats.messagesInResendBuffer);
            BANDWIDTH_LIMITED
                    .labelValues(username)
                    .set(stats.isLimitedByOutgoingBandwidthLimit ? 1 : 0);
            BPS_LIMIT_OUTGOING.labelValues(username).set(stats.bpsLimitByOutgoingBandwidthLimit);

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
            SEND_BUFFER_MESSAGES.labelValues(prev, "immediate").set(0.0);
            SEND_BUFFER_MESSAGES.labelValues(prev, "high").set(0.0);
            SEND_BUFFER_MESSAGES.labelValues(prev, "medium").set(0.0);
            SEND_BUFFER_MESSAGES.labelValues(prev, "low").set(0.0);
            RESEND_BUFFER_MESSAGES.labelValues(prev).set(0.0);
            BANDWIDTH_LIMITED.labelValues(prev).set(0.0);
            BPS_LIMIT_OUTGOING.labelValues(prev).set(0.0);
            consecutiveTicksOverThreshold.remove(prev);
        }
        lastSeenUsernames.clear();
        lastSeenUsernames.addAll(currentUsernames);

        if (toKick != null) {
            for (UdpConnection c : toKick) {
                String label = labelFor(c);
                String username = label != null ? label : "guid:" + c.getConnectedGUID();
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

    /**
     * The per-peer metric label, or {@code null} until the connection has a username. A guid-based
     * fallback would mint a label set per connection attempt that nothing ever removes, so the
     * registry would grow with uptime rather than with players. A shared placeholder label is not
     * an option either: every nameless peer would {@code set()} over the last one.
     */
    @Nullable
    private static String labelFor(UdpConnection c) {
        String name = c.getUserName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return null;
    }
}
