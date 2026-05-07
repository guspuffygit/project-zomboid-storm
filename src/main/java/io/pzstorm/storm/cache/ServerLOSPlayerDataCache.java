package io.pzstorm.storm.cache;

import java.util.IdentityHashMap;
import zombie.characters.IsoPlayer;

/**
 * Identity-keyed side cache for {@code zombie.network.ServerLOS$PlayerData} lookups.
 *
 * <p>The base game's {@code ServerLOS.findData(IsoPlayer)} does a linear scan over an {@code
 * ArrayList<PlayerData>} on every call, comparing by reference. With many connected players, this
 * scan becomes the dominant cost in {@code IsoPlayer.updateLOS()} (which calls it per moving object
 * in the cell). This cache reduces the lookup to O(1).
 *
 * <p>Values are stored as {@code Object} because {@code PlayerData} is a private static inner class
 * and cannot be referenced directly from the advice package.
 */
public final class ServerLOSPlayerDataCache {

    private static final IdentityHashMap<IsoPlayer, Object> CACHE = new IdentityHashMap<>();

    private ServerLOSPlayerDataCache() {}

    public static Object get(IsoPlayer player) {
        synchronized (CACHE) {
            return CACHE.get(player);
        }
    }

    public static void put(IsoPlayer player, Object data) {
        synchronized (CACHE) {
            CACHE.put(player, data);
        }
    }

    public static void remove(IsoPlayer player) {
        synchronized (CACHE) {
            CACHE.remove(player);
        }
    }

    public static int size() {
        synchronized (CACHE) {
            return CACHE.size();
        }
    }
}
