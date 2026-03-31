package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;

/** Dispatched on the server when a player accepts or declines a safehouse invite. */
public class SafehouseInviteRespondedEvent implements ZomboidEvent {

    public final String invitedPlayer;
    public final String owner;
    public final boolean accepted;
    public final long steamId;
    public final int x;
    public final int y;
    public final int w;
    public final int h;
    public final String title;

    public SafehouseInviteRespondedEvent(
            String invitedPlayer,
            String owner,
            boolean accepted,
            long steamId,
            int x,
            int y,
            int w,
            int h,
            String title) {
        this.invitedPlayer = invitedPlayer;
        this.owner = owner;
        this.accepted = accepted;
        this.steamId = steamId;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.title = title;
    }

    @Override
    public String getName() {
        return "SafehouseInviteResponded";
    }
}
