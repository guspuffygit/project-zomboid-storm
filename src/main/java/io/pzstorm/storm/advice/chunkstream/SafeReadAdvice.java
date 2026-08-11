package io.pzstorm.storm.advice.chunkstream;

import io.pzstorm.storm.metrics.ChunkHydrationMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Times {@code IsoChunk.SafeRead}, the disk read a download worker performs for any chunk that is
 * not resident in {@code ServerMap}.
 *
 * <p>This is a serial component of {@code storm_chunk_stream_batch_duration_seconds}, and the batch
 * is what holds a peer's single dispatch slot. Worth timing separately because the lock discipline
 * makes it a cross-thread coupling point rather than plain I/O: {@code acquireLock} is a linear
 * scan under a global monitor, and the per-chunk lock is a <em>fair</em> {@code
 * ReentrantReadWriteLock} held across the whole file read, so one queued writer parks every later
 * reader behind it.
 */
public class SafeReadAdvice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        return GameServer.server ? System.nanoTime() : 0L;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        ChunkHydrationMetrics.recordDiskRead(
                ChunkHydrationMetrics.callerRole(), System.nanoTime() - startNanos);
    }
}
