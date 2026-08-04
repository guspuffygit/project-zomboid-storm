package io.pzstorm.storm.cache;

import java.util.concurrent.ConcurrentHashMap;
import zombie.characters.IsoPlayer;

/**
 * Side cache for {@code zombie.network.ServerLOS$PlayerData} lookups.
 *
 * <p>The base game's {@code ServerLOS.findData(IsoPlayer)} does a linear scan over an {@code
 * ArrayList<PlayerData>} on every call, comparing by reference. With many connected players, this
 * scan becomes the dominant cost in {@code IsoPlayer.updateLOS()} (which calls it per moving object
 * in the cell). This cache reduces the lookup to O(1).
 *
 * <p>Backed by a {@link ConcurrentHashMap} so the per-call {@code get} is lock-free: the previous
 * {@code synchronized} {@code IdentityHashMap} took a contended monitor on every {@code isCouldSee}
 * call, fighting the LOS worker threads. {@code IsoPlayer} does not override {@code equals}/{@code
 * hashCode}, so hashing keeps the same identity semantics.
 *
 * <p>Values are stored as {@code Object} because {@code PlayerData} is a private static inner class
 * and cannot be referenced directly from the advice package.
 */
public final class ServerLOSPlayerDataCache {

    private static final ConcurrentHashMap<IsoPlayer, Object> CACHE = new ConcurrentHashMap<>();

    private ServerLOSPlayerDataCache() {}

    public static Object get(IsoPlayer player) {
        return CACHE.get(player);
    }

    public static void put(IsoPlayer player, Object data) {
        CACHE.put(player, data);
    }

    public static void remove(IsoPlayer player) {
        CACHE.remove(player);
    }

    public static int size() {
        return CACHE.size();
    }
}
