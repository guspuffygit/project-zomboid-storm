package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;

/** Dispatched on the server when a player places an item or object on the map. */
public class ItemPlacedOnMapEvent implements ZomboidEvent {

    public final String username;
    public final long steamId;
    public final String itemType;
    public final int x;
    public final int y;
    public final int z;
    public final boolean isWorldInventoryItem;

    public ItemPlacedOnMapEvent(
            String username,
            long steamId,
            String itemType,
            int x,
            int y,
            int z,
            boolean isWorldInventoryItem) {
        this.username = username;
        this.steamId = steamId;
        this.itemType = itemType;
        this.x = x;
        this.y = y;
        this.z = z;
        this.isWorldInventoryItem = isWorldInventoryItem;
    }

    @Override
    public String getName() {
        return "ItemPlacedOnMap";
    }
}
