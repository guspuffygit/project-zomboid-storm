package io.pzstorm.storm.advice.chunkload;

import io.pzstorm.storm.metrics.ChunkLoadMetrics;
import io.pzstorm.storm.metrics.MainLoopStepTimings;
import io.pzstorm.storm.patch.performance.StormChunkPreloadHelper;
import io.pzstorm.storm.patch.performance.StormChunkPreloadState;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoChunk;
import zombie.network.GameServer;

public class IsoChunkLoadAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.This IsoChunk chunk, @Advice.Local("startNanos") long startNanos) {
        if (!GameServer.server) {
            startNanos = 0L;
            return false;
        }
        if (StormChunkPreloadState.consume(chunk)) {
            startNanos = 0L;
            return true;
        }
        startNanos = System.nanoTime();
        return false;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Local("startNanos") long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        ChunkLoadMetrics.recordNanos(elapsed);
        String bucket =
                Boolean.TRUE.equals(StormChunkPreloadHelper.IN_PRELOAD.get())
                        ? "IsoChunk.doLoadGridsquare.preload"
                        : "IsoChunk.doLoadGridsquare";
        MainLoopStepTimings.record(bucket, elapsed);
    }
}
