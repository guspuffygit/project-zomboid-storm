package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;

/** Dispatched on the server when a player drops held (equipped) items. */
public class HeldItemsDroppedEvent implements ZomboidEvent {

    public final String username;
    public final long steamId;
    public final String primaryItemType;
    public final String secondaryItemType;
    public final int x;
    public final int y;
    public final int z;
    public final boolean isThrow;

    public HeldItemsDroppedEvent(
            String username,
            long steamId,
            String primaryItemType,
            String secondaryItemType,
            int x,
            int y,
            int z,
            boolean isThrow) {
        this.username = username;
        this.steamId = steamId;
        this.primaryItemType = primaryItemType;
        this.secondaryItemType = secondaryItemType;
        this.x = x;
        this.y = y;
        this.z = z;
        this.isThrow = isThrow;
    }

    @Override
    public String getName() {
        return "HeldItemsDropped";
    }
}
