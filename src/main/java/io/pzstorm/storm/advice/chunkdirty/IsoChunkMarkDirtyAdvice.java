package io.pzstorm.storm.advice.chunkdirty;

import io.pzstorm.storm.metrics.ChunkDirtyTracker;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoChunk;
import zombie.network.GameServer;

public class IsoChunkMarkDirtyAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.This IsoChunk chunk) {
        if (!GameServer.server) {
            return;
        }
        if (chunk == null) {
            return;
        }
        ChunkDirtyTracker.markDirty(chunk.wx, chunk.wy);
    }
}
