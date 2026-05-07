package io.pzstorm.storm.advice.chunkdirty;

import io.pzstorm.storm.metrics.ChunkDirtyTracker;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.network.GameServer;

public class ErosionMainLoadGridsquareAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Argument(0) IsoGridSquare square) {
        if (!GameServer.server) {
            return;
        }
        if (square == null) {
            return;
        }
        IsoChunk chunk = square.getChunk();
        if (chunk == null) {
            return;
        }
        ChunkDirtyTracker.markDirty(chunk.wx, chunk.wy);
    }
}
