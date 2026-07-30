package io.pzstorm.storm.metrics;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.datapoints.GaugeDataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.pzstorm.storm.advice.gameserverstalledconnections.StalledConnectionReaper;
import io.pzstorm.storm.connection.ConnectionStage;
import io.pzstorm.storm.connection.RakNetConnectionCapConfig;
import java.util.Arrays;
import java.util.List;
import zombie.core.raknet.RakNetPeerInterface;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.network.GameServer;

/**
 * The login funnel and RakNet slot budget, sampled every server tick from {@link
 * io.pzstorm.storm.advice.servertick.ServerTickAdvice} (server-only).
 *
 * <p>This is the view that was missing when a full RakNet peer took the server offline: vanilla
 * exports spawned-player counts and per-parameter network aggregates, so a peer filling up with
 * connections that never became players was invisible right up to the point where every new joiner
 * silently wedged on "Getting Server Info...". These series make each stage of {@link
 * ConnectionStage} countable, ageable, and alertable, and put slot occupancy next to the cap in
 * force.
 *
 * <p>Everything is sampled on the server main thread and pushed into plain gauges rather than read
 * from scrape-time callbacks — {@code UdpEngine.connections}, {@code LoginQueue}'s monitor and the
 * RakNet peer are all main-thread state, and touching them from an HTTP scrape thread is the lock
 * inversion that has frozen this server before.
 *
 * <ul>
 *   <li>{@code storm_connections{stage}} — connections per pipeline stage. Sums to {@code
 *       storm_connection_slots_used}.
 *   <li>{@code storm_connection_stage_age_seconds_max{stage}} — oldest connection in each stage,
 *       measured from the first tick Storm sampled it.
 *   <li>{@code storm_connection_slots_used} / {@code storm_connection_slots_max} — occupancy
 *       against the cap actually handed to RakNet. When these meet, RakNet answers new joiners with
 *       {@code ID_NO_FREE_INCOMING_CONNECTIONS} and the vanilla client shows no error at all.
 *   <li>{@code storm_connection_raknet_peers} — RakNet's own peer count, which counts handshakes
 *       {@code UdpEngine} has not wrapped in a {@code UdpConnection} yet. Above {@code
 *       storm_connection_slots_used} means slots are occupied by peers the Java side cannot see.
 *   <li>{@code storm_connection_login_duration_seconds} — accept → spawned, for connections Storm
 *       watched through the whole funnel.
 *   <li>{@code storm_connection_reap_age_seconds_max} / {@code
 *       storm_connection_reaped_total{stage}} — how close the oldest stalled connection is to
 *       {@code storm_connection_reap_timeout_seconds}, and what the reaper has actually taken.
 * </ul>
 */
public final class StormConnectionStageMetrics {

    private static final Gauge CONNECTIONS =
            Gauge.builder()
                    .name("storm_connections")
                    .help(
                            "Connections holding a RakNet slot, broken down by login-pipeline stage."
                                    + " Mutually exclusive, so the stages sum to"
                                    + " storm_connection_slots_used. Only stage=\"fully_connected\""
                                    + " counts against MaxPlayers; every other stage is a"
                                    + " pre-spawn connection that still consumes a slot.")
                    .labelNames("stage")
                    .register(StormPrometheus.registry());

    private static final Gauge STAGE_AGE_SECONDS_MAX =
            Gauge.builder()
                    .name("storm_connection_stage_age_seconds_max")
                    .help(
                            "Age of the oldest connection currently in each stage, measured from the"
                                    + " first server tick Storm sampled that connection (so it is a"
                                    + " lower bound for connections that predate server startup"
                                    + " completing). 0 when the stage is empty. A pre-spawn stage"
                                    + " climbing into the minutes is the slot leak that exhausts the"
                                    + " RakNet peer.")
                    .labelNames("stage")
                    .register(StormPrometheus.registry());

    private static final Gauge SLOTS_USED =
            Gauge.builder()
                    .name("storm_connection_slots_used")
                    .help(
                            "RakNet incoming-connection slots in use (GameServer.udpEngine.connections"
                                    + " size). Counts every connection at any stage of the login"
                                    + " pipeline, not just players.")
                    .register(StormPrometheus.registry());

    private static final Gauge SLOTS_MAX =
            Gauge.builder()
                    .name("storm_connection_slots_max")
                    .help(
                            "RakNet incoming-connection cap actually in force"
                                    + " (UdpEngine.getMaxConnections()). Vanilla hard-codes 101"
                                    + " regardless of MaxPlayers; Storm raises it to MaxPlayers +"
                                    + " storm.raknet.connectionHeadroom, clamped to 256 by the"
                                    + " byte-wide wire index. When storm_connection_slots_used"
                                    + " reaches this, new joiners are refused at the RakNet level"
                                    + " with no client-visible error.")
                    .register(StormPrometheus.registry());

    private static final Gauge RAKNET_PEERS =
            Gauge.builder()
                    .name("storm_connection_raknet_peers")
                    .help(
                            "Peer count reported by RakNet itself"
                                    + " (RakNetPeerInterface.GetConnectionsNumber()). Exceeds"
                                    + " storm_connection_slots_used while RakNet holds peers that"
                                    + " UdpEngine has not wrapped in a UdpConnection yet, which is"
                                    + " slot occupancy invisible to every other metric here. Stays"
                                    + " at 0 with a WARN in storm/main.log if the native is"
                                    + " unavailable.")
                    .register(StormPrometheus.registry());

    private static final Gauge REAP_AGE_SECONDS_MAX =
            Gauge.builder()
                    .name("storm_connection_reap_age_seconds_max")
                    .help(
                            "Largest time-on-the-reap-clock across all connections that are not"
                                    + " fully connected. Distinct from"
                                    + " storm_connection_stage_age_seconds_max: the reaper restamps"
                                    + " this clock while a connection is exempt (login queue, co-op"
                                    + " approve, pending second-factor auth), so this is the number"
                                    + " compared against storm_connection_reap_timeout_seconds.")
                    .register(StormPrometheus.registry());

    private static final Gauge REAP_TIMEOUT_SECONDS =
            Gauge.builder()
                    .name("storm_connection_reap_timeout_seconds")
                    .help(
                            "Wall-clock budget a connection gets to finish logging in and spawn"
                                    + " before Storm frees its RakNet slot. Default 420s (7 min),"
                                    + " overridden by -Dstorm.reapStalledConnectionMs.")
                    .register(StormPrometheus.registry());

    private static final Gauge REAP_SWEEP_INTERVAL_SECONDS =
            Gauge.builder()
                    .name("storm_connection_reap_sweep_interval_seconds")
                    .help(
                            "How often the stalled-connection sweep runs, which is also the"
                                    + " granularity of storm_connection_reap_age_seconds_max and the"
                                    + " worst-case overshoot past the reap timeout. Default 30s,"
                                    + " overridden by -Dstorm.reapSweepIntervalMs.")
                    .register(StormPrometheus.registry());

    private static final Gauge CAP_VANILLA =
            Gauge.builder()
                    .name("storm_connection_cap_vanilla")
                    .help(
                            "The incoming-connection cap vanilla GameServer.startServer hard-codes."
                                    + " Constant baseline so a dashboard can show the headroom Storm"
                                    + " added as storm_connection_slots_max - this.")
                    .register(StormPrometheus.registry());

    private static final Gauge CAP_FALLBACK =
            Gauge.builder()
                    .name("storm_connection_cap_fallback")
                    .help(
                            "1 when RakNet refused to start with Storm's raised cap and the server"
                                    + " fell back to constructing the peer with the vanilla cap, else"
                                    + " 0. A 1 here means the headroom is not actually in place —"
                                    + " see the accompanying error in storm/main.log and consider"
                                    + " -Dstorm.raknet.connectionHeadroom=0.")
                    .register(StormPrometheus.registry());

    private static final Counter REAPED =
            Counter.builder()
                    .name("storm_connection_reaped_total")
                    .help(
                            "Connections whose RakNet slot Storm freed for outstaying the connect"
                                    + " budget, labelled by the stage they were stuck in."
                                    + " stage=\"handshake\" is a dead pre-login handshake;"
                                    + " stage=\"awaiting_spawn\" is a client that authenticated and"
                                    + " then never spawned (the \"Click Start\" camper). Every reap"
                                    + " is also logged at WARN in storm/main.log with the"
                                    + " connection id.")
                    .labelNames("stage")
                    .register(StormPrometheus.registry());

    private static final Histogram LOGIN_DURATION_SECONDS =
            Histogram.builder()
                    .name("storm_connection_login_duration_seconds")
                    .help(
                            "Wall-clock time from Storm first sampling a connection to its character"
                                    + " spawning (setFullyConnected). Observed once per connection,"
                                    + " and only for connections Storm saw in a pre-spawn stage"
                                    + " first — connections already spawned at the first sample are"
                                    + " skipped rather than reported as instant.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    /** Matches {@code UdpEngine.connectionArray}, which is a fixed 256 entries. */
    private static final int MAX_SLOTS = 256;

    private static final long[] FIRST_SAMPLED_MS = new long[MAX_SLOTS];
    private static final long[] SLOT_GUID = new long[MAX_SLOTS];
    private static final boolean[] SEEN_PENDING = new boolean[MAX_SLOTS];
    private static final boolean[] LOGIN_OBSERVED = new boolean[MAX_SLOTS];

    private static final GaugeDataPoint[] COUNT_BY_STAGE =
            new GaugeDataPoint[ConnectionStage.ALL.length];
    private static final GaugeDataPoint[] AGE_BY_STAGE =
            new GaugeDataPoint[ConnectionStage.ALL.length];
    private static final CounterDataPoint[] REAPED_BY_STAGE =
            new CounterDataPoint[ConnectionStage.ALL.length];

    private static final int[] stageCounts = new int[ConnectionStage.ALL.length];
    private static final long[] stageAgeMaxMs = new long[ConnectionStage.ALL.length];

    private static boolean rakNetPeerCountAvailable = true;

    static {
        for (int i = 0; i < ConnectionStage.ALL.length; i++) {
            String stage = ConnectionStage.ALL[i];
            COUNT_BY_STAGE[i] = CONNECTIONS.labelValues(stage);
            AGE_BY_STAGE[i] = STAGE_AGE_SECONDS_MAX.labelValues(stage);
            REAPED_BY_STAGE[i] = REAPED.labelValues(stage);
        }
        CAP_VANILLA.set(RakNetConnectionCapConfig.VANILLA_CAP);
        CAP_FALLBACK.set(0);
    }

    private StormConnectionStageMetrics() {}

    /**
     * Samples every connection once. Must run on the server main thread — see the class comment.
     *
     * <p>All stage series are written every tick, including empty ones, so a stage that empties
     * reads as a step down to 0 rather than a stale flat line.
     */
    public static void recordAll() {
        UdpEngine engine = GameServer.udpEngine;
        if (engine == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Arrays.fill(stageCounts, 0);
        Arrays.fill(stageAgeMaxMs, 0L);
        long reapAgeMaxMs = 0L;

        List<UdpConnection> connections = engine.connections;
        for (int i = 0; i < connections.size(); i++) {
            UdpConnection connection = connections.get(i);
            if (connection == null) {
                continue;
            }
            int stage = ConnectionStage.indexOf(ConnectionStage.classify(connection));
            if (stage < 0) {
                continue;
            }
            stageCounts[stage]++;

            boolean fullyConnected = connection.isFullyConnected();
            long ageMs = trackSlot(connection, now, fullyConnected);
            if (ageMs > stageAgeMaxMs[stage]) {
                stageAgeMaxMs[stage] = ageMs;
            }
            if (!fullyConnected) {
                long reapAgeMs = StalledConnectionReaper.getReapAgeMs(connection, now);
                if (reapAgeMs > reapAgeMaxMs) {
                    reapAgeMaxMs = reapAgeMs;
                }
            }
        }

        for (int stage = 0; stage < ConnectionStage.ALL.length; stage++) {
            COUNT_BY_STAGE[stage].set(stageCounts[stage]);
            AGE_BY_STAGE[stage].set(stageAgeMaxMs[stage] / 1000.0);
        }
        SLOTS_USED.set(connections.size());
        SLOTS_MAX.set(engine.getMaxConnections());
        RAKNET_PEERS.set(readRakNetPeerCount(engine));
        REAP_AGE_SECONDS_MAX.set(reapAgeMaxMs / 1000.0);
        REAP_TIMEOUT_SECONDS.set(StalledConnectionReaper.getConnectTimeoutMs() / 1000.0);
        REAP_SWEEP_INTERVAL_SECONDS.set(StalledConnectionReaper.getSweepIntervalMs() / 1000.0);
    }

    /**
     * Stamps first-sighting state for the connection's slot and returns its age in millis. Observes
     * {@link #LOGIN_DURATION_SECONDS} on the sample where a watched connection first reads as fully
     * connected.
     */
    private static long trackSlot(UdpConnection connection, long now, boolean fullyConnected) {
        int slot = connection.getIndex();
        if (slot < 0 || slot >= MAX_SLOTS) {
            return 0L;
        }
        long guid = connection.getConnectedGUID();
        if (SLOT_GUID[slot] != guid || FIRST_SAMPLED_MS[slot] == 0L) {
            SLOT_GUID[slot] = guid;
            FIRST_SAMPLED_MS[slot] = now;
            SEEN_PENDING[slot] = !fullyConnected;
            LOGIN_OBSERVED[slot] = false;
            return 0L;
        }
        long ageMs = now - FIRST_SAMPLED_MS[slot];
        if (!fullyConnected) {
            SEEN_PENDING[slot] = true;
        } else if (SEEN_PENDING[slot] && !LOGIN_OBSERVED[slot]) {
            LOGIN_OBSERVED[slot] = true;
            LOGIN_DURATION_SECONDS.observe(ageMs / 1000.0);
        }
        return ageMs;
    }

    private static double readRakNetPeerCount(UdpEngine engine) {
        if (!rakNetPeerCountAvailable) {
            return 0.0;
        }
        try {
            RakNetPeerInterface peer = engine.getPeer();
            return peer == null ? 0.0 : peer.GetConnectionsNumber();
        } catch (Throwable t) {
            rakNetPeerCountAvailable = false;
            LOGGER.warn(
                    "Storm: RakNetPeerInterface.GetConnectionsNumber() is unavailable —"
                            + " storm_connection_raknet_peers will stay at 0",
                    t);
            return 0.0;
        }
    }

    /**
     * Counts a slot freed by {@link StalledConnectionReaper}, attributed to the stage it was in.
     */
    public static void recordReaped(String stage) {
        int index = ConnectionStage.indexOf(stage);
        if (index >= 0) {
            REAPED_BY_STAGE[index].inc();
        }
    }

    /**
     * Publishes the cap {@code GameServer.startServer} actually handed to RakNet, before the first
     * tick has had a chance to read it off the live engine.
     */
    public static void setResolvedCap(int cap) {
        SLOTS_MAX.set(cap);
    }

    /** Flags that the raised cap failed and the peer was built with the vanilla cap instead. */
    public static void setCapFallback(boolean fellBack) {
        CAP_FALLBACK.set(fellBack ? 1 : 0);
    }
}
