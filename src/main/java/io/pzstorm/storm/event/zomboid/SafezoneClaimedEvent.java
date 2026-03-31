package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;

/** Dispatched on the server when an admin creates a safezone via the admin tools. */
public class SafezoneClaimedEvent implements ZomboidEvent {

    public final String username;
    public final long steamId;
    public final int x;
    public final int y;
    public final int w;
    public final int h;
    public final String title;

    public SafezoneClaimedEvent(
            String username, long steamId, int x, int y, int w, int h, String title) {
        this.username = username;
        this.steamId = steamId;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.title = title;
    }

    @Override
    public String getName() {
        return "SafezoneClaimed";
    }
}
