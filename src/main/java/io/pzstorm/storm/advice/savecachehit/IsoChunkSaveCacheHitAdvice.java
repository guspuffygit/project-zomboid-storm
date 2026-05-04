package io.pzstorm.storm.advice.savecachehit;

import io.pzstorm.storm.metrics.ChunkDirtyTracker;
import io.pzstorm.storm.metrics.ChunkSaveCacheHitMetrics;
import java.util.zip.CRC32;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoChunk;
import zombie.network.GameServer;

public class IsoChunkSaveCacheHitAdvice {

    @Advice.OnMethodEnter
    public static boolean onEnter(@Advice.This IsoChunk chunk) {
        if (!GameServer.server) {
            return false;
        }
        if (chunk == null) {
            return false;
        }
        return !ChunkDirtyTracker.isDirty(chunk.wx, chunk.wy);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.This IsoChunk chunk,
            @Advice.Argument(1) CRC32 crc,
            @Advice.Enter boolean wasClean) {
        if (!GameServer.server) {
            return;
        }
        if (chunk == null || crc == null) {
            return;
        }
        ChunkSaveCacheHitMetrics.observe(chunk.wx, chunk.wy, crc.getValue(), wasClean);
        ChunkDirtyTracker.markClean(chunk.wx, chunk.wy);
    }
}
