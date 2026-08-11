package io.pzstorm.storm.advice.chunkstream;

import io.pzstorm.storm.metrics.ChunkStreamMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.ClientChunkRequest;
import zombie.network.GameServer;

/**
 * Counts chunks that actually reach the wire, and the bytes they cost.
 *
 * <p>{@code compressChunk} is the single point where both sizes are available: the argument still
 * holds the uncompressed buffer and the return value is the deflated length. It is called exactly
 * once per {@code sendChunk}, which makes it the honest supply-side counter — {@code sendArray}
 * also answers requests with {@code NotRequiredInZip}, and those cost nothing but would inflate a
 * batch-level count.
 *
 * <p>Runs on the download worker thread.
 */
public class CompressChunkAdvice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        if (!GameServer.server) {
            return 0L;
        }
        return System.nanoTime();
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Argument(0) ClientChunkRequest.Chunk chunk,
            @Advice.Return int compressedBytes,
            @Advice.Enter long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        int rawBytes = chunk != null && chunk.bb != null ? chunk.bb.limit() : 0;
        ChunkStreamMetrics.recordCompressed(
                rawBytes, compressedBytes, System.nanoTime() - startNanos);
    }
}
