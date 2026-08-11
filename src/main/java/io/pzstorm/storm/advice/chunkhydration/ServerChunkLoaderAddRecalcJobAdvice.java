package io.pzstorm.storm.advice.chunkhydration;

import io.pzstorm.storm.metrics.ChunkHydrationMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Closes the load stage and opens the recalc stage for one {@code ServerCell}.
 *
 * <p>{@code ServerMap.preupdate} drains the LoadChunk thread's output and immediately calls {@code
 * addRecalcJob} for each cell, so this boundary is both "the loader finished with it" and "the
 * RecalcAll thread now owns it". Splitting there is what separates a disk/worldgen bottleneck from
 * a recalc bottleneck — two single-threaded stages with unbounded queues that need different fixes.
 *
 * <p>Runs on the server main thread. Pure measurement.
 */
public class ServerChunkLoaderAddRecalcJobAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.Argument(0) Object cell) {
        if (!GameServer.server) {
            return;
        }
        ChunkHydrationMetrics.recordCellRecalcQueued(cell);
    }
}
