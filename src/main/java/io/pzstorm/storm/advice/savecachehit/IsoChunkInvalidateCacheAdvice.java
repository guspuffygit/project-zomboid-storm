package io.pzstorm.storm.advice.savecachehit;

import io.pzstorm.storm.metrics.ChunkSaveCache;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoChunk;
import zombie.network.GameServer;

public class IsoChunkInvalidateCacheAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This IsoChunk chunk) {
        if (!GameServer.server) {
            return;
        }
        if (chunk == null) {
            return;
        }
        ChunkSaveCache.invalidate(chunk.wx, chunk.wy);
    }
}
