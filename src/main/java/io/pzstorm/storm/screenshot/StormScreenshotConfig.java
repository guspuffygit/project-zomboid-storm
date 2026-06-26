package io.pzstorm.storm.screenshot;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime knob for the client-side screenshot uploader in {@code
 * media/lua/client/StormScreenshot.lua}.
 *
 * <p>Controls how many 24573-byte base64 pieces are packed into a single {@code sendClientCommand}
 * packet when the client streams a captured PNG back to the server. The hard ceiling is {@link
 * #MAX} = 28 because vanilla {@code zombie.core.raknet.UdpConnection}'s outbound ByteBuffer is 1 MB
 * and 28 maxed pieces serialize to ~918 KB (with ~82 KB headroom for outer-table overhead); above
 * 30 the ByteBufferWriter throws {@code BufferOverflowException} mid-send.
 *
 * <p>The default of {@link #DEFAULT_PIECES_PER_PACKET} = 4 (~131 KB per packet) prevents the
 * client-side RakNet keepalive timeout (~10 s) from firing mid-upload on saturated home uplinks:
 * smaller, more frequent packets let server ACKs and downstream traffic interleave instead of being
 * starved out by a multi-second 918 KB burst. Admins on low-latency wired networks can raise the
 * knob toward {@link #MAX} for faster uploads.
 *
 * <p>Sandbox-loaded from {@code Storm.ScreenshotPiecesPerPacket} at {@code OnServerStarted} and on
 * every {@code OnSandboxOptionsUpdate} via {@link
 * io.pzstorm.storm.sandbox.StormPerformanceSandboxApplier}. The synced value is read on the client
 * each capture via {@code SandboxVars.Storm.ScreenshotPiecesPerPacket}; this Java mirror exists for
 * server-side observability through the Prometheus gauge.
 */
public final class StormScreenshotConfig {

    public static final int MIN = 1;
    public static final int MAX = 28;
    public static final int DEFAULT_PIECES_PER_PACKET = 4;

    private static final AtomicInteger PIECES_PER_PACKET =
            new AtomicInteger(clamp(DEFAULT_PIECES_PER_PACKET));

    private StormScreenshotConfig() {}

    public static int getPiecesPerPacket() {
        return PIECES_PER_PACKET.get();
    }

    public static int setPiecesPerPacket(int n) {
        int clamped = clamp(n);
        PIECES_PER_PACKET.set(clamped);
        StormPerformanceSandboxMetrics.setScreenshotPiecesPerPacket(clamped);
        return clamped;
    }

    private static int clamp(int n) {
        return Math.max(MIN, Math.min(MAX, n));
    }
}
