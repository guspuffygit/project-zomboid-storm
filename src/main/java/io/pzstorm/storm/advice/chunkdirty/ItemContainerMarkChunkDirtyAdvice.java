package io.pzstorm.storm.advice.chunkdirty;

import io.pzstorm.storm.metrics.ChunkDirtyTracker;
import net.bytebuddy.asm.Advice;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.network.GameServer;

public class ItemContainerMarkChunkDirtyAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.This ItemContainer container) {
        if (!GameServer.server) {
            return;
        }
        if (container == null) {
            return;
        }
        IsoObject parent = container.getParent();
        IsoGridSquare square = null;
        if (parent != null) {
            square = parent.getSquare();
        }
        if (square == null) {
            square = container.getSourceGrid();
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
