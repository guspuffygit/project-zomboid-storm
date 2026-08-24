package io.pzstorm.storm.advice.serversoundindex;

import io.pzstorm.storm.sound.StormServerChunkSoundIndex;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Enter advice on {@code WorldSoundManager.KillCell()}: un-indexes every tracked sound before
 * vanilla releases the whole list back to its object pool on world teardown ({@link
 * StormServerChunkSoundIndex#onKillCell}).
 */
public class WorldSoundManagerKillCellAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This Object manager) {
        if (GameServer.server) {
            StormServerChunkSoundIndex.onKillCell(manager);
        }
    }
}
