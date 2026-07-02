package io.pzstorm.storm.screenshot;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime knobs for the client-side screenshot uploader in {@code
 * media/lua/client/StormScreenshot.lua}. All three are sandbox-loaded on {@code OnServerStarted}
 * and every {@code OnSandboxOptionsUpdate} via {@link
 * io.pzstorm.storm.sandbox.StormPerformanceSandboxApplier}; the synced values are read on the
 * client each capture via {@code SandboxVars.Storm.*}. These Java mirrors exist for server-side
 * observability through Prometheus gauges.
 *
 * <ul>
 *   <li><b>{@link #getPiecesPerPacket()}</b> — wire framing only: how many 24573-byte base64 pieces
 *       ride in one {@code sendClientCommand} packet. The hard ceiling {@link #PIECES_MAX} = 28
 *       (~918 KB/packet) keeps a maxed packet under vanilla {@code
 *       zombie.core.raknet.UdpConnection}'s 1 MB outbound ByteBuffer; above ~30 the
 *       ByteBufferWriter throws {@code BufferOverflowException} mid-send. This is <em>not</em> the
 *       throttle.
 *   <li><b>{@link #getUploadKbPerSec()}</b> — the disconnect fix: a wall-clock throughput cap
 *       (KiB/s of base64 wire bytes) that keeps the client's upload well under its uplink so
 *       RakNet's ACK/keepalive traffic keeps flowing. Without it, a large-resolution screenshot
 *       builds a reliable-ordered send backlog longer than RakNet's ~10 s connection timeout and
 *       the player is dropped with "Connection Lost".
 *   <li><b>{@link #getEncodeKbPerTick()}</b> — the client-lag fix: caps how many source KiB are
 *       base64-encoded per client tick on the single Lua main thread, so encoding never stalls
 *       rendering for the whole upload.
 * </ul>
 */
public final class StormScreenshotConfig {

    public static final int PIECES_MIN = 1;
    public static final int PIECES_MAX = 28;
    public static final int DEFAULT_PIECES_PER_PACKET = 4;

    public static final int UPLOAD_KB_PER_SEC_MIN = 8;
    public static final int UPLOAD_KB_PER_SEC_MAX = 4096;
    public static final int DEFAULT_UPLOAD_KB_PER_SEC = 128;

    public static final int ENCODE_KB_PER_TICK_MIN = 1;
    public static final int ENCODE_KB_PER_TICK_MAX = 64;
    public static final int DEFAULT_ENCODE_KB_PER_TICK = 4;

    private static final AtomicInteger PIECES_PER_PACKET =
            new AtomicInteger(clampPieces(DEFAULT_PIECES_PER_PACKET));
    private static final AtomicInteger UPLOAD_KB_PER_SEC =
            new AtomicInteger(clampUploadKbPerSec(DEFAULT_UPLOAD_KB_PER_SEC));
    private static final AtomicInteger ENCODE_KB_PER_TICK =
            new AtomicInteger(clampEncodeKbPerTick(DEFAULT_ENCODE_KB_PER_TICK));

    private StormScreenshotConfig() {}

    public static int getPiecesPerPacket() {
        return PIECES_PER_PACKET.get();
    }

    public static int setPiecesPerPacket(int n) {
        int clamped = clampPieces(n);
        PIECES_PER_PACKET.set(clamped);
        StormPerformanceSandboxMetrics.setScreenshotPiecesPerPacket(clamped);
        return clamped;
    }

    public static int getUploadKbPerSec() {
        return UPLOAD_KB_PER_SEC.get();
    }

    public static int setUploadKbPerSec(int n) {
        int clamped = clampUploadKbPerSec(n);
        UPLOAD_KB_PER_SEC.set(clamped);
        StormPerformanceSandboxMetrics.setScreenshotUploadKbPerSec(clamped);
        return clamped;
    }

    public static int getEncodeKbPerTick() {
        return ENCODE_KB_PER_TICK.get();
    }

    public static int setEncodeKbPerTick(int n) {
        int clamped = clampEncodeKbPerTick(n);
        ENCODE_KB_PER_TICK.set(clamped);
        StormPerformanceSandboxMetrics.setScreenshotEncodeKbPerTick(clamped);
        return clamped;
    }

    private static int clampPieces(int n) {
        return Math.max(PIECES_MIN, Math.min(PIECES_MAX, n));
    }

    private static int clampUploadKbPerSec(int n) {
        return Math.max(UPLOAD_KB_PER_SEC_MIN, Math.min(UPLOAD_KB_PER_SEC_MAX, n));
    }

    private static int clampEncodeKbPerTick(int n) {
        return Math.max(ENCODE_KB_PER_TICK_MIN, Math.min(ENCODE_KB_PER_TICK_MAX, n));
    }
}
