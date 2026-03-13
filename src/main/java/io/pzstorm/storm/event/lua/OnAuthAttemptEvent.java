package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/** Triggered on the server when a client authentication attempt completes. */
public class OnAuthAttemptEvent implements LuaEvent {

    public final String username;
    public final String ip;
    public final long steamId;
    public final int authType;
    public final boolean authorized;
    public final String dcReason;
    public final String bannedReason;

    public OnAuthAttemptEvent(
            String username,
            String ip,
            long steamId,
            int authType,
            boolean authorized,
            String dcReason,
            String bannedReason) {
        this.username = username;
        this.ip = ip;
        this.steamId = steamId;
        this.authType = authType;
        this.authorized = authorized;
        this.dcReason = dcReason;
        this.bannedReason = bannedReason;
    }
}
