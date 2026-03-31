package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;

/** Dispatched on the server when safehouse ownership is transferred to another player. */
public class SafehouseOwnerChangedEvent implements ZomboidEvent {

    public final String previousOwner;
    public final String newOwner;
    public final long steamId;
    public final int x;
    public final int y;
    public final int w;
    public final int h;
    public final String title;

    public SafehouseOwnerChangedEvent(
            String previousOwner,
            String newOwner,
            long steamId,
            int x,
            int y,
            int w,
            int h,
            String title) {
        this.previousOwner = previousOwner;
        this.newOwner = newOwner;
        this.steamId = steamId;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.title = title;
    }

    @Override
    public String getName() {
        return "SafehouseOwnerChanged";
    }
}
