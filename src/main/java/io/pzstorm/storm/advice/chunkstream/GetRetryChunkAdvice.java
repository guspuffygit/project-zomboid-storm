package io.pzstorm.storm.advice.chunkstream;

import io.pzstorm.storm.metrics.ChunkStreamMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.ClientChunkRequest;
import zombie.network.GameServer;

/**
 * Counts the retry ladder — the server telling itself "I do not have this chunk yet, ask me again".
 *
 * <p>{@code sendArray} reaches {@code getRetryChunk} only when {@code ServerMap} had no loaded
 * chunk and no save file exists on disk for those coordinates, which is precisely the state a
 * player driving into unhydrated territory creates. The retry is appended to the back of {@code
 * ccrWaiting}, so every rung costs at least one more dispatch slot on a queue that is already the
 * bottleneck, and a null return means the client gets {@code NotRequiredInZip} and keeps a hole in
 * the world.
 *
 * <p>Runs on the download worker thread. {@code getRetryChunk} has exactly one caller ({@code
 * PlayerDownloadServer.sendArray}), so nothing else is counted here.
 */
public class GetRetryChunkAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.Return ClientChunkRequest.Chunk retryChunk) {
        if (!GameServer.server) {
            return;
        }
        ChunkStreamMetrics.recordRetry(retryChunk == null ? 0 : retryChunk.retriesCount);
    }
}
