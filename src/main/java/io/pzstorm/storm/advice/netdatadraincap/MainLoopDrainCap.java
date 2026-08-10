package io.pzstorm.storm.advice.netdatadraincap;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.NetDataMetrics;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;
import zombie.network.ZomboidNetData;

/**
 * Runtime knob for {@link MainLoopDrainCapAdvice}.
 *
 * <p>The HIGH-priority and player-update inbound queues in {@code GameServer.main}'s outer loop are
 * drained without any per-spin time bound (only the vehicle queue has a built-in 70&nbsp;ms cap).
 * During a reconnect storm this allows a single outer-loop iteration to spend hundreds of
 * milliseconds inside {@code mainLoopDealWithNetData} — collapsing TPS, starving the world tick,
 * and overflowing RakNet's outbound buffer (observed: 107&nbsp;MB, followed by JVM crash).
 *
 * <p>The cap implemented here bounds the cumulative wall-clock time spent in {@code
 * mainLoopDealWithNetData} per outer-loop spin. Once {@link #burstStartNanos} + cap is exceeded,
 * subsequent calls in the same spin short-circuit (the packet is dropped and returned to the pool,
 * same as the vehicle queue's existing overflow behavior at {@code GameServer.java:902-915}).
 * Dropped packets are NOT retransmitted — RakNet ACKed them at the transport layer before they were
 * queued, whatever their declared reliability — so while the cap is engaged one-shot reliable
 * packets are lost, not deferred; see {@link MainLoopDrainCapAdvice} for why that tradeoff is
 * accepted. Two packet classes are exempt from the drop: {@code VehicleRequest} (the only path that
 * regenerates a lost vehicle for a client) and anything from a connection that has not finished the
 * join handshake ({@link #isPreJoinExempt}).
 *
 * <p>"Spin boundary" is detected by a gap of more than {@link #BURST_GAP_NANOS} between consecutive
 * advice invocations. Calls within the same drain section happen back-to-back (sub-microsecond
 * gaps); between outer-loop iterations the main thread either sleeps ≥5&nbsp;ms or runs the
 * ~80&nbsp;ms frame-step block. A 1&nbsp;ms gap cleanly separates the two.
 *
 * <p>Sourced from the {@code Storm.NetDataCapMs} sandbox option, which feeds through {@link
 * #setCapMs(int)} at {@code OnServerStarted} and again on every {@code OnSandboxOptionsUpdate} so
 * admins can adjust the cap live without restarting the server. {@code 0} disables the cap entirely
 * (matches vanilla behaviour).
 */
public final class MainLoopDrainCap {

    public static final int MIN_CAP_MS = 0;
    public static final int MAX_CAP_MS = 200;

    static final long BURST_GAP_NANOS = 1_000_000L;

    // Cap stays disabled until OnServerStarted fires the sandbox applier and the configured value
    // (default 90 ms from media/sandbox-options.txt) is pushed in via setCapMs. During cold start
    // the main thread spends tens of ms inside mainLoopDealWithNetData on first-touch packet types
    // (lazy ClassLoader + StormClassTransformer pass + JIT). With the cap active at that point,
    // legitimate login packets get short-circuited and clients fail to complete the handshake
    // (regression: ActionByteIdCollisionLiveTest, June 2026).
    private static volatile long CAP_NANOS = 0L;

    public static volatile long lastCallEndNanos = 0L;
    public static volatile long burstStartNanos = 0L;

    private static final long EXEMPT_WARN_INTERVAL_NANOS = 5_000_000_000L;

    // Main-thread writers only (the advice runs inside GameServer.main's outer loop), so plain
    // fields are safe for the warn throttle.
    private static long lastExemptWarnNanos = 0L;
    private static long exemptSinceLastWarn = 0L;

    private MainLoopDrainCap() {}

    /**
     * True when the over-budget packet belongs to a connection that has not completed the join
     * handshake — {@code UdpConnection.isFullyConnected()} flips only in {@code
     * GameServer.receivePlayerConnect}, so this covers the whole login funnel (Login,
     * LoginQueueRequest, Checksum, LoginQueueDone, PlayerConnect) and the world-download phase.
     *
     * <p>Every packet in that funnel is sent exactly once and never retried by the vanilla client
     * ({@code LoadingQueueState.update} returns Remain forever after its single send), so dropping
     * one silently strands the join: the client hangs on the loading screen until Storm's stalled
     * connection reaper kills it. Observed live: a cold-start {@code LoginPacket.processServer}
     * taking 474&nbsp;ms blew the budget and the {@code LoginQueueRequest} queued in the same burst
     * was dropped. Pre-join traffic is tiny and bounded by the connection cap and the reaper, so
     * processing it under an engaged cap is cheap.
     *
     * <p>Records {@code pz_netdata_prejoin_exempt_total} and a throttled WARN. Main-thread only.
     */
    public static boolean isPreJoinExempt(ZomboidNetData data) {
        if (GameServer.udpEngine == null) {
            return false;
        }
        UdpConnection connection = GameServer.udpEngine.getActiveConnection(data.connection);
        if (connection == null || connection.isFullyConnected()) {
            return false;
        }
        String type = data.type == null ? "unknown" : data.type.name();
        NetDataMetrics.recordPreJoinExempt(type);
        exemptSinceLastWarn++;
        long now = System.nanoTime();
        if (now - lastExemptWarnNanos > EXEMPT_WARN_INTERVAL_NANOS) {
            LOGGER.warn(
                    "Storm: net-data drain cap engaged during a join; processed pre-join packet"
                            + " {} for {} (guid {}) anyway ({} exempted since last report)",
                    type,
                    connection.getIP(),
                    data.connection,
                    exemptSinceLastWarn);
            lastExemptWarnNanos = now;
            exemptSinceLastWarn = 0L;
        }
        return true;
    }

    /** Current cap in nanoseconds; {@code 0} when the cap is disabled. Hot-path reader. */
    public static long getCapNanos() {
        return CAP_NANOS;
    }

    /**
     * Updates the per-spin drain cap. Clamps to {@link #MIN_CAP_MS}..{@link #MAX_CAP_MS}, stores
     * the value as nanoseconds for the advice hot path, and pushes the millisecond value to the
     * Prometheus gauge. {@code 0} disables the cap entirely. Returns the applied (clamped) value.
     */
    public static int setCapMs(int requestedMs) {
        int clamped = clamp(requestedMs);
        CAP_NANOS = (long) clamped * 1_000_000L;
        StormPerformanceSandboxMetrics.setNetDataCapMs(clamped);
        return clamped;
    }

    private static int clamp(int requested) {
        if (requested < MIN_CAP_MS) {
            return MIN_CAP_MS;
        }
        if (requested > MAX_CAP_MS) {
            return MAX_CAP_MS;
        }
        return requested;
    }
}
