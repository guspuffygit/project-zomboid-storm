package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

@SuppressWarnings({"WeakerAccess", "unused"})
public class OnSetDefaultTabEvent implements LuaEvent {

    // TODO: document this event
    public final String tabName;

    public OnSetDefaultTabEvent(String tabName) {
        this.tabName = tabName;
    }
}
