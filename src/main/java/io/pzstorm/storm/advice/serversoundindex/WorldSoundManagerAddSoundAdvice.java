package io.pzstorm.storm.advice.serversoundindex;

import io.pzstorm.storm.sound.StormServerChunkSoundIndex;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Exit advice on the 13-arg body overload of {@code WorldSoundManager.addSound}: indexes the
 * returned {@code WorldSound} into the per-chunk sound lists on the server via {@link
 * StormServerChunkSoundIndex#onSoundAdded}. Only the body overload is advised — the other {@code
 * addSound} overloads delegate to it, so advising by bare name would index each sound multiple
 * times.
 */
public class WorldSoundManagerAddSoundAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.Return Object sound) {
        if (GameServer.server) {
            StormServerChunkSoundIndex.onSoundAdded(sound);
        }
    }
}
