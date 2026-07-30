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
 *
 * <p>{@code onThrowable} makes the eviction run on exceptional exit too: if {@code removePlayer}
 * throws after {@code playersMain.remove(data)} (e.g. a null {@code thread.notifier}), a skipped
 * eviction would leave a cache entry for a {@code PlayerData} no longer in the list — and {@code
 * addPlayer} would then refuse to ever re-add that player.
 */
public class ServerLOSRemovePlayerAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
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
