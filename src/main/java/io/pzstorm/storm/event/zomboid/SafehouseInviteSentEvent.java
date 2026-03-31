package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;

/** Dispatched on the server when a safehouse owner sends an invite to another player. */
public class SafehouseInviteSentEvent implements ZomboidEvent {

    public final String owner;
    public final String invited;
    public final long steamId;
    public final int x;
    public final int y;
    public final int w;
    public final int h;
    public final String title;

    public SafehouseInviteSentEvent(
            String owner, String invited, long steamId, int x, int y, int w, int h, String title) {
        this.owner = owner;
        this.invited = invited;
        this.steamId = steamId;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.title = title;
    }

    @Override
    public String getName() {
        return "SafehouseInviteSent";
    }
}
