package io.pzstorm.storm.advice.chunkstream;

import io.pzstorm.storm.metrics.ChunkHydrationMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Times {@code ChunkChecksum.getChecksum}, which every download worker calls once per chunk it
 * serves from disk.
 *
 * <p>The method body is one {@code synchronized (checksumCache)} block, and on a cache miss it does
 * a full file read inside it through a single shared {@code CRC32} and a single shared 1 KB byte
 * array. Those shared statics are why the lock has to be global — which makes this the one place
 * where every player's chunk streaming contends with every other player's. Timing it separately is
 * what distinguishes "this peer's disk is slow" from "this peer is queued behind another peer".
 */
public class GetChecksumAdvice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        return GameServer.server ? System.nanoTime() : 0L;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        ChunkHydrationMetrics.recordChecksum(
                ChunkHydrationMetrics.callerRole(), System.nanoTime() - startNanos);
    }
}
