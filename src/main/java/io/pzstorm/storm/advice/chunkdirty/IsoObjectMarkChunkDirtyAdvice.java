package io.pzstorm.storm.advice.chunkdirty;

import io.pzstorm.storm.metrics.ChunkDirtyTracker;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.network.GameServer;

public class IsoObjectMarkChunkDirtyAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.This IsoObject obj) {
        if (!GameServer.server) {
            return;
        }
        if (obj == null) {
            return;
        }
        IsoGridSquare square = obj.getSquare();
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
