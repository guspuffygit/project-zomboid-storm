package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/** Triggered when connection to server has failed. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnConnectFailedEvent implements LuaEvent {

    /** Connection failure reason. */
    public final String reason;

    public OnConnectFailedEvent(String reason) {
        this.reason = reason;
    }

    public OnConnectFailedEvent() {
        this.reason = "";
    }
}
