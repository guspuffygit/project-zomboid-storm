package io.pzstorm.storm.connection;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;

/**
 * Runtime threshold for the per-peer send-buffer watchdog driven by {@code
 * io.pzstorm.storm.metrics.StormConnectionMetrics#recordAll()}.
 *
 * <p>When a peer's {@code bytesInSendBufferHigh} stays above this many megabytes for {@code
 * StormConnectionMetrics.KICK_HOLD_TICKS} consecutive server ticks (~5 s at 10 TPS), that peer is
 * force-disconnected with reason {@code storm-send-buffer-overflow}.
 *
 * <p>PZ queues HIGH-priority broadcasts (Weather, SyncClock, faction sync, ClientCommand, region
 * events, in-flight chunk data) to every {@code fullyConnected} peer without any check on send
 * buffer depth or ACK progress (verified across {@code INetworkPacket.sendToAll}, {@code
 * PlayerDownloadServer.sendChunk}, {@code RakNetPeerInterface.Send}). A peer on a saturated or
 * lossy uplink whose RakNet congestion control has throttled outbound BPS to near zero therefore
 * accumulates the server's full broadcast volume in their per-peer queue indefinitely. Observed
 * single-peer growth: 0 -> 42&nbsp;MB over 40 minutes before an admin manually kicked.
 *
 * <p>Sourced from the {@code Storm.PeerSendBufferKickMb} sandbox option; the value flows through
 * {@link #setKickMb(int)} at {@code OnServerStarted} and again on every {@code
 * OnSandboxOptionsUpdate} so admins can adjust live. {@code 0} disables the watchdog entirely (the
 * per-peer telemetry gauges keep populating either way).
 */
public final class PeerSendBufferKickConfig {

    public static final int MIN_MB = 0;
    public static final int MAX_MB = 1000;
    public static final int DEFAULT_MB = 0;

    private static volatile long THRESHOLD_BYTES = 0L;

    private PeerSendBufferKickConfig() {}

    /** Current threshold in bytes; {@code 0} when the watchdog is disabled. Hot-path reader. */
    public static long thresholdBytes() {
        return THRESHOLD_BYTES;
    }

    public static boolean enabled() {
        return THRESHOLD_BYTES > 0L;
    }

    /**
     * Updates the per-peer send-buffer kick threshold. Clamps to {@link #MIN_MB}..{@link #MAX_MB},
     * stores the value as bytes for the hot path, and pushes the megabyte value to the Prometheus
     * gauge. {@code 0} disables the watchdog entirely. Returns the applied (clamped) value.
     */
    public static int setKickMb(int requestedMb) {
        int clamped = clamp(requestedMb);
        THRESHOLD_BYTES = (long) clamped * 1024L * 1024L;
        StormPerformanceSandboxMetrics.setPeerSendBufferKickMb(clamped);
        return clamped;
    }

    private static int clamp(int requested) {
        if (requested < MIN_MB) {
            return MIN_MB;
        }
        if (requested > MAX_MB) {
            return MAX_MB;
        }
        return requested;
    }
}
