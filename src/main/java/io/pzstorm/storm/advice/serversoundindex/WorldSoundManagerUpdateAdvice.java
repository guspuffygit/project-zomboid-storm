package io.pzstorm.storm.advice.serversoundindex;

import io.pzstorm.storm.sound.StormServerChunkSoundIndex;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Enter advice on {@code WorldSoundManager.update()}: un-indexes the sounds the vanilla sweep is
 * about to remove and release to its object pool this tick, before the pooled instances can be
 * recycled ({@link StormServerChunkSoundIndex#onUpdateStart}).
 */
public class WorldSoundManagerUpdateAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This Object manager) {
        if (GameServer.server) {
            StormServerChunkSoundIndex.onUpdateStart(manager);
        }
    }
}
