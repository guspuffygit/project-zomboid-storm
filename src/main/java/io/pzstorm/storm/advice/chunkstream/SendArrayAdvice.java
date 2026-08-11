package io.pzstorm.storm.advice.chunkstream;

import io.pzstorm.storm.metrics.ChunkStreamMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.ClientChunkRequest;
import zombie.network.GameServer;

/**
 * Times one download-worker batch and classifies the chunks in it.
 *
 * <p>This span is exactly the window in which the worker's {@code ready} flag is false, and {@code
 * PlayerDownloadServer.update} dispatches nothing at all for that peer while it is — not even
 * duplicate pruning. It is therefore the per-peer service time that the one-batch-per-tick dispatch
 * rule is queued behind.
 *
 * <p>Runs on the {@code PlayerDownloadServer*} worker thread, never the main thread. It only reads
 * the request's own chunk list and increments Prometheus counters, both of which are safe off-main;
 * it takes no game lock and touches no connection state.
 */
public class SendArrayAdvice {

    @Advice.OnMethodEnter
    public static long onEnter(@Advice.Argument(0) ClientChunkRequest ccr) {
        if (!GameServer.server) {
            return 0L;
        }
        ChunkStreamMetrics.recordBatchStart(ccr);
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        ChunkStreamMetrics.recordBatchDuration(System.nanoTime() - startNanos);
    }
}
