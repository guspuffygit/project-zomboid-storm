package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;

/** Dispatched on the server when a player drops an item from inventory to the floor. */
public class ItemTransferredToFloorEvent implements ZomboidEvent {

    public final String username;
    public final long steamId;
    public final String itemFullType;
    public final String itemName;
    public final int x;
    public final int y;
    public final int z;

    public ItemTransferredToFloorEvent(
            String username,
            long steamId,
            String itemFullType,
            String itemName,
            int x,
            int y,
            int z) {
        this.username = username;
        this.steamId = steamId;
        this.itemFullType = itemFullType;
        this.itemName = itemName;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public String getName() {
        return "ItemTransferredToFloor";
    }
}
