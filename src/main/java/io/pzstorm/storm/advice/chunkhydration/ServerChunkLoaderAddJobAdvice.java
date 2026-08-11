package io.pzstorm.storm.advice.chunkhydration;

import io.pzstorm.storm.metrics.ChunkHydrationMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Starts the hydration clock for one {@code ServerCell}.
 *
 * <p>{@code ServerChunkLoader.addJob} is the only way a cell reaches the LoadChunk thread, and it
 * has exactly one call site — {@code ServerMap.preupdate}, on the line before it sets {@code
 * startedLoading}. Stamping here rather than at {@code loadOrKeepRelevent} deliberately excludes
 * the request-to-dispatch gap, which {@code preupdate} closes within a single tick because it
 * submits every eligible pending cell in one unbudgeted loop.
 *
 * <p>Runs on the server main thread. Pure measurement.
 */
public class ServerChunkLoaderAddJobAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.Argument(0) Object cell) {
        if (!GameServer.server) {
            return;
        }
        ChunkHydrationMetrics.recordCellQueued(cell);
    }
}
