package io.pzstorm.storm.advice.serverlosfinddata;

import io.pzstorm.storm.cache.ServerLOSPlayerDataCache;
import net.bytebuddy.asm.Advice;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;

/**
 * Evicts a player's {@code PlayerData} entry from {@link ServerLOSPlayerDataCache} after the base
 * game removes them from {@code playersMain}.
 *
 * <p>Runs on method exit so the original {@code findData(player)} call inside {@code removePlayer}
 * can still see the cached entry to remove from the underlying list. After the list removal
 * succeeds, the cache entry is now stale and must be evicted.
 */
public class ServerLOSRemovePlayerAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.Argument(0) IsoPlayer player) {
        if (!GameServer.server) {
            return;
        }
        if (player == null) {
            return;
        }
        ServerLOSPlayerDataCache.remove(player);
    }
}
