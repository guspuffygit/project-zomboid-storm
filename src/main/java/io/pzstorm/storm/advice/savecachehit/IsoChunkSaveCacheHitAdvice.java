package io.pzstorm.storm.advice.savecachehit;

import io.pzstorm.storm.metrics.ChunkSaveCacheHitMetrics;
import java.util.zip.CRC32;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoChunk;
import zombie.network.GameServer;

public class IsoChunkSaveCacheHitAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.This IsoChunk chunk, @Advice.Argument(1) CRC32 crc) {
        if (!GameServer.server) {
            return;
        }
        if (chunk == null || crc == null) {
            return;
        }
        ChunkSaveCacheHitMetrics.observe(chunk.wx, chunk.wy, crc.getValue());
    }
}
