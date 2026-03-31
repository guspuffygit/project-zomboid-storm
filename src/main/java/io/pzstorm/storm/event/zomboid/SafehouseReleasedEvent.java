package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;

/** Dispatched on the server when a safehouse owner releases their safehouse. */
public class SafehouseReleasedEvent implements ZomboidEvent {

    public final String owner;
    public final long steamId;
    public final int x;
    public final int y;
    public final int w;
    public final int h;
    public final String title;
    public final String members;

    public SafehouseReleasedEvent(
            String owner, long steamId, int x, int y, int w, int h, String title, String members) {
        this.owner = owner;
        this.steamId = steamId;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.title = title;
        this.members = members;
    }

    @Override
    public String getName() {
        return "SafehouseReleased";
    }
}
