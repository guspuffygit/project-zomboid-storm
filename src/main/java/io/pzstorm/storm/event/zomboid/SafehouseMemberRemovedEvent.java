package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;

/** Dispatched on the server when a member is removed from a safehouse. */
public class SafehouseMemberRemovedEvent implements ZomboidEvent {

    public final String removedPlayer;
    public final String owner;
    public final long steamId;
    public final int x;
    public final int y;
    public final int w;
    public final int h;
    public final String title;

    public SafehouseMemberRemovedEvent(
            String removedPlayer,
            String owner,
            long steamId,
            int x,
            int y,
            int w,
            int h,
            String title) {
        this.removedPlayer = removedPlayer;
        this.owner = owner;
        this.steamId = steamId;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.title = title;
    }

    @Override
    public String getName() {
        return "SafehouseMemberRemoved";
    }
}
