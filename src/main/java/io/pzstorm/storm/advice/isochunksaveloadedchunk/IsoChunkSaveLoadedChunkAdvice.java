package io.pzstorm.storm.advice.isochunksaveloadedchunk;

import io.pzstorm.storm.metrics.ChunkStreamMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * {@code SaveLoadedChunk} is the main-thread half of chunk streaming — {@code
 * PlayerDownloadServer.update} calls it once per chunk it can serve inline, up to 20 per connection
 * per tick. The step-timings line aggregates it into the tick breakdown; the Prometheus histogram
 * makes the per-call distribution and the call rate visible on their own, which is what tells apart
 * "many cheap chunks" from "a few expensive ones".
 *
 * <p>Saving reaches the same method on two routes. {@code ServerCell.Save} goes through {@code
 * ServerChunkLoader.addSaveLoadedJob}, on the main thread during {@code SaveAll} and on a {@code
 * ServerMap} worker otherwise. An unloading chunk goes through {@code
 * ServerChunkLoader$SaveUnloadedTask.serialize}, on the save thread or on a LoadChunk worker that
 * needs the pending bytes. {@link ChunkStreamMetrics#recordSerialize(long)} separates saving from
 * streaming by the thread-local marker that {@code ServerChunkLoaderSaveLoadedJobAdvice} sets on
 * both save routes, and owns the step-timings line as well so both decisions are made in one place.
 */
public class IsoChunkSaveLoadedChunkAdvice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        if (!GameServer.server) {
            return 0L;
        }
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long startNanos) {
        if (!GameServer.server) {
            return;
        }
        if (startNanos == 0L) {
            return;
        }
        ChunkStreamMetrics.recordSerialize(System.nanoTime() - startNanos);
    }
}
