package io.pzstorm.storm.advice.chunkdirty;

import io.pzstorm.storm.metrics.ChunkDirtyTracker;
import net.bytebuddy.asm.Advice;
import zombie.entity.GameEntity;
import zombie.iso.IsoChunk;
import zombie.iso.IsoObject;
import zombie.network.GameServer;

public class GameEntityManagerRegisterEntityAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Argument(0) GameEntity entity) {
        if (!GameServer.server) {
            return;
        }
        if (!(entity instanceof IsoObject)) {
            return;
        }
        IsoObject obj = (IsoObject) entity;
        IsoChunk chunk = obj.getChunk();
        if (chunk == null) {
            return;
        }
        ChunkDirtyTracker.markDirty(chunk.wx, chunk.wy);
    }
}
