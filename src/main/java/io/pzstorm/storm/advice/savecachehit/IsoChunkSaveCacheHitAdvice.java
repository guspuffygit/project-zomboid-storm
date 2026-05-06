package io.pzstorm.storm.advice.savecachehit;

import io.pzstorm.storm.metrics.ChunkDirtyTracker;
import io.pzstorm.storm.metrics.ChunkSaveCache;
import io.pzstorm.storm.metrics.ChunkSaveCacheHitMetrics;
import io.pzstorm.storm.metrics.ChunkSaveDiffLogger;
import java.util.zip.CRC32;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoChunk;
import zombie.network.ClientChunkRequest;
import zombie.network.GameServer;

public class IsoChunkSaveCacheHitAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.This IsoChunk chunk, @Advice.Argument(0) ClientChunkRequest.Chunk ccrc) {
        if (!GameServer.server) {
            return false;
        }
        if (chunk == null || ccrc == null) {
            return false;
        }
        if (!ChunkSaveCache.enabled()) {
            return false;
        }
        if (ChunkDirtyTracker.isDirty(chunk.wx, chunk.wy)) {
            return false;
        }
        return ChunkSaveCache.populate(chunk.wx, chunk.wy, ccrc);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.This IsoChunk chunk,
            @Advice.Argument(0) ClientChunkRequest.Chunk ccrc,
            @Advice.Argument(1) CRC32 crc,
            @Advice.Enter boolean skipped,
            @Advice.Thrown Throwable thrown) {
        if (!GameServer.server) {
            return;
        }
        if (chunk == null) {
            return;
        }
        if (thrown != null) {
            return;
        }
        if (!skipped && ccrc != null && ccrc.bb != null) {
            long crcVal = crc != null ? crc.getValue() : 0L;
            boolean wasClean = !ChunkDirtyTracker.isDirty(chunk.wx, chunk.wy);
            byte[] prevBytes = ChunkSaveCache.peek(chunk.wx, chunk.wy);
            boolean cleanMiss =
                    ChunkSaveCacheHitMetrics.observe(chunk.wx, chunk.wy, crcVal, wasClean);
            if (cleanMiss) {
                ChunkSaveDiffLogger.log(chunk.wx, chunk.wy, prevBytes, ccrc.bb);
            }
            ChunkSaveCache.store(chunk.wx, chunk.wy, ccrc.bb);
        }
        ChunkDirtyTracker.markClean(chunk.wx, chunk.wy);
    }
}
