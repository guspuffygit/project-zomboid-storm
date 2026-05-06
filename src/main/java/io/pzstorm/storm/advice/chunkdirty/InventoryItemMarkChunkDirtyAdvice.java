package io.pzstorm.storm.advice.chunkdirty;

import io.pzstorm.storm.metrics.ChunkDirtyTracker;
import net.bytebuddy.asm.Advice;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.network.GameServer;

public class InventoryItemMarkChunkDirtyAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.This InventoryItem item) {
        if (!GameServer.server) {
            return;
        }
        if (item == null) {
            return;
        }
        IsoGridSquare square = null;
        IsoWorldInventoryObject worldItem = item.getWorldItem();
        if (worldItem != null) {
            square = worldItem.getSquare();
        } else {
            ItemContainer outer = item.getOutermostContainer();
            if (outer != null && outer.getSourceGrid() != null) {
                square = outer.getSourceGrid();
            }
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
