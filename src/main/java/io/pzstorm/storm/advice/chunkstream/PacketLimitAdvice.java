package io.pzstorm.storm.advice.chunkstream;

import io.pzstorm.storm.client.ClientChunkStreamMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameClient;
import zombie.network.PacketTypes;

/**
 * Counts outgoing packets the client silently drops for exceeding {@code MaxPacketsPerSecond}.
 *
 * <p>{@code PacketType.send} is the only caller that acts on this result: {@code if
 * (GameClient.client && connection.isLimitExceeded(this)) connection.cancelPacket();}. It then
 * returns normally, so the caller cannot tell the packet went nowhere. For chunk streaming that is
 * the worst possible failure — {@code WorldStreamer.updateMain} has already moved those requests
 * into {@code sentRequests}, and the client has no resend timer so nothing ever retries them: the
 * server never saw the request, so its ChunkNotReady timeout never arms, and the chunk stays
 * missing until the chunk map re-wants it. The server's own call site only logs the result and
 * sends anyway, which is why this is gated on {@code GameClient.client}: it isolates the call that
 * actually drops.
 *
 * <p>Fails soft by design. The catch covers {@code ExceptionInInitializerError} and {@code
 * NoClassDefFoundError} from the metrics class, which are {@code Error}s and would otherwise escape
 * into the send path of every packet the client writes.
 */
public class PacketLimitAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Argument(0) PacketTypes.PacketType packetType,
            @Advice.Return boolean exceeded) {
        if (!exceeded || !GameClient.client) {
            return;
        }
        try {
            ClientChunkStreamMetrics.recordSuppressedPacket(packetType.name());
        } catch (Throwable ignored) {
            // a dropped metric must never become a dropped packet's second failure
        }
    }
}
