package io.pzstorm.storm.advice.serverlosfinddata;

import io.pzstorm.storm.cache.ServerLOSPlayerDataCache;
import io.pzstorm.storm.metrics.ServerLOSFindDataMetrics;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;

/**
 * Replaces the linear {@code playersMain} scan in {@code ServerLOS.findData(IsoPlayer)} with an
 * O(1) {@link ServerLOSPlayerDataCache} lookup.
 *
 * <p>{@code findData} is called per moving object inside {@code IsoPlayer.updateLOS()} (via {@code
 * ServerLOS.isCouldSee}), so on a server with many players and zombies the original {@code for (i =
 * 0; i < playersMain.size(); i++)} loop dominates main-thread CPU. This advice short- circuits the
 * body when the player is already cached and populates the cache on miss.
 *
 * <p>Cache invalidation lives in {@link ServerLOSRemovePlayerAdvice}.
 *
 * <p>Pattern: same shape as {@code UIWorldMapV1Patch.GetOptionByIndexAdvice} &mdash; enter advice
 * returns the cached value (skips body when non-null); exit advice writes it back via
 * {@code @Advice.Return(readOnly = false, typing = DYNAMIC)} (DYNAMIC because the original method
 * returns the private {@code ServerLOS$PlayerData} type which we cannot reference directly).
 *
 * <p>Returns the original method untouched on the client &mdash; this cache is only useful on a
 * dedicated server (clients have at most one {@code playersMain} entry).
 */
public class ServerLOSFindDataAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object onEnter(@Advice.Argument(0) IsoPlayer player) {
        if (!GameServer.server) {
            return null;
        }
        if (player == null) {
            return null;
        }
        Object cached = ServerLOSPlayerDataCache.get(player);
        if (cached != null) {
            ServerLOSFindDataMetrics.recordHit();
        }
        return cached;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Argument(0) IsoPlayer player,
            @Advice.Enter Object cached,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object result) {
        if (!GameServer.server) {
            return;
        }
        if (cached != null) {
            result = cached;
            return;
        }
        ServerLOSFindDataMetrics.recordMiss();
        if (result != null && player != null) {
            ServerLOSPlayerDataCache.put(player, result);
        }
    }
}
