package io.pzstorm.storm.advice.chunkstream;

import io.pzstorm.storm.metrics.ChunkStreamMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Starts the clock on {@code storm_chunk_stream_queue_wait_seconds}.
 *
 * <p>{@code PlayerDownloadServer.getClientChunkRequest} is an exact 1:1 funnel for the request
 * queue: each of the four {@code ccrWaiting.add} sites — two fresh ones in {@code
 * RequestZipListPacket.parse}, two retry ones in {@code PlayerDownloadServer} — allocates through
 * it on the immediately preceding line, and nothing else does. Stamping the returned request
 * therefore timestamps every enqueue with no lock and no queue-side bookkeeping to keep in sync.
 *
 * <p>Stamping on acquisition also survives the {@code freeRequests} pool: a recycled request is
 * re-stamped before its next use, so a stale timestamp can never leak into a later batch. The
 * matching stop is in {@code ChunkStreamMetrics.recordBatchStart}, already invoked from {@link
 * SendArrayAdvice} as the worker picks the request up.
 *
 * <p>Runs on the packet-parse thread and on the download worker thread. Pure measurement.
 */
public class ClientChunkRequestEnqueueAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.Return Object ccr) {
        if (!GameServer.server) {
            return;
        }
        ChunkStreamMetrics.stampEnqueue(ccr);
    }
}
