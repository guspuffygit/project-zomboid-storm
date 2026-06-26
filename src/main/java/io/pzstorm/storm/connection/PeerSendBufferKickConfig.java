package io.pzstorm.storm.connection;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;

/**
 * Runtime config for the per-peer send-buffer watchdog driven by {@code
 * io.pzstorm.storm.metrics.StormConnectionMetrics#recordAll()}.
 *
 * <p>When a peer's {@code bytesInSendBufferHigh} stays above {@link #thresholdBytes()} for {@link
 * #holdTicks()} consecutive server ticks, that peer is force-disconnected with reason {@code
 * storm-send-buffer-overflow}.
 *
 * <p>PZ queues HIGH-priority broadcasts (Weather, SyncClock, faction sync, ClientCommand, region
 * events, in-flight chunk data) to every {@code fullyConnected} peer without any check on send
 * buffer depth or ACK progress (verified across {@code INetworkPacket.sendToAll}, {@code
 * PlayerDownloadServer.sendChunk}, {@code RakNetPeerInterface.Send}). A peer on a saturated or
 * lossy uplink whose RakNet congestion control has throttled outbound BPS to near zero therefore
 * accumulates the server's full broadcast volume in their per-peer queue indefinitely. Observed
 * single-peer growth: 0 -> 42&nbsp;MB over 40 minutes before an admin manually kicked.
 *
 * <p>Both knobs flow from sandbox options through their setters at {@code OnServerStarted} and on
 * every {@code OnSandboxOptionsUpdate} so admins can adjust live:
 *
 * <ul>
 *   <li>{@code Storm.PeerSendBufferKickMb} → {@link #setKickMb(int)}. {@code 0} disables the
 *       watchdog entirely (the per-peer telemetry gauges keep populating either way).
 *   <li>{@code Storm.PeerSendBufferKickHoldTicks} → {@link #setHoldTicks(int)}. Number of
 *       consecutive over-threshold ticks before the kick fires. At the vanilla 10&nbsp;TPS, 50
 *       ticks = 5&nbsp;seconds — long enough to ignore single-tick broadcast spikes, short enough
 *       to fire well before the buffer reaches a JVM-endangering size.
 * </ul>
 */
public final class PeerSendBufferKickConfig {

    public static final int MIN_MB = 0;
    public static final int MAX_MB = 1000;
    public static final int DEFAULT_MB = 20;

    public static final int MIN_HOLD_TICKS = 1;
    public static final int MAX_HOLD_TICKS = 6000;
    public static final int DEFAULT_HOLD_TICKS = 50;

    private static volatile long THRESHOLD_BYTES = (long) DEFAULT_MB * 1024L * 1024L;
    private static volatile int HOLD_TICKS = DEFAULT_HOLD_TICKS;

    private PeerSendBufferKickConfig() {}

    /** Current threshold in bytes; {@code 0} when the watchdog is disabled. Hot-path reader. */
    public static long thresholdBytes() {
        return THRESHOLD_BYTES;
    }

    public static boolean enabled() {
        return THRESHOLD_BYTES > 0L;
    }

    /** Number of consecutive over-threshold ticks before the kick fires. Hot-path reader. */
    public static int holdTicks() {
        return HOLD_TICKS;
    }

    /**
     * Updates the per-peer send-buffer kick threshold. Clamps to {@link #MIN_MB}..{@link #MAX_MB},
     * stores the value as bytes for the hot path, and pushes the megabyte value to the Prometheus
     * gauge. {@code 0} disables the watchdog entirely. Returns the applied (clamped) value.
     */
    public static int setKickMb(int requestedMb) {
        int clamped = clampMb(requestedMb);
        THRESHOLD_BYTES = (long) clamped * 1024L * 1024L;
        StormPerformanceSandboxMetrics.setPeerSendBufferKickMb(clamped);
        return clamped;
    }

    /**
     * Updates the consecutive-tick hold window. Clamps to {@link #MIN_HOLD_TICKS}..{@link
     * #MAX_HOLD_TICKS} and pushes the value to the Prometheus gauge. Returns the applied (clamped)
     * value.
     */
    public static int setHoldTicks(int requestedTicks) {
        int clamped = clampHoldTicks(requestedTicks);
        HOLD_TICKS = clamped;
        StormPerformanceSandboxMetrics.setPeerSendBufferKickHoldTicks(clamped);
        return clamped;
    }

    private static int clampMb(int requested) {
        if (requested < MIN_MB) {
            return MIN_MB;
        }
        if (requested > MAX_MB) {
            return MAX_MB;
        }
        return requested;
    }

    private static int clampHoldTicks(int requested) {
        if (requested < MIN_HOLD_TICKS) {
            return MIN_HOLD_TICKS;
        }
        if (requested > MAX_HOLD_TICKS) {
            return MAX_HOLD_TICKS;
        }
        return requested;
    }
}
