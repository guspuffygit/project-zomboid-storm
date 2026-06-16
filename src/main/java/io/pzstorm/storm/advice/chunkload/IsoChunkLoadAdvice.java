package io.pzstorm.storm.advice.chunkload;

import io.pzstorm.storm.metrics.ChunkLoadMetrics;
import io.pzstorm.storm.metrics.MainLoopStepTimings;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class IsoChunkLoadAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Local("startNanos") long startNanos) {
        startNanos = GameServer.server ? System.nanoTime() : 0L;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Local("startNanos") long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        ChunkLoadMetrics.recordNanos(elapsed);
        MainLoopStepTimings.record("IsoChunk.doLoadGridsquare", elapsed);
    }
}
