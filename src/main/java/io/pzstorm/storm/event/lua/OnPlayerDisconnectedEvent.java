package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/** Triggered on the server when a player disconnects. */
public class OnPlayerDisconnectedEvent implements LuaEvent {

    public final String username;
    public final String displayName;
    public final String ip;
    public final long steamId;
    public final String idStr;
    public final String roleName;
    public final long connectedGuid;
    public final short onlineId;
    public final float x;
    public final float y;
    public final float z;

    public OnPlayerDisconnectedEvent(
            String username,
            String displayName,
            String ip,
            long steamId,
            String idStr,
            String roleName,
            long connectedGuid,
            short onlineId,
            float x,
            float y,
            float z) {
        this.username = username;
        this.displayName = displayName;
        this.ip = ip;
        this.steamId = steamId;
        this.idStr = idStr;
        this.roleName = roleName;
        this.connectedGuid = connectedGuid;
        this.onlineId = onlineId;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
