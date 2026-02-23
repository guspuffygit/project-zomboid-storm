package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/** Trigger after resetting Lua scripts. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnResetLuaEvent implements LuaEvent {

    public final String reason;

    @Override
    public String getName() {
        return "OnResetLua";
    }

    public OnResetLuaEvent(String reason) {
        this.reason = reason;
    }
}
