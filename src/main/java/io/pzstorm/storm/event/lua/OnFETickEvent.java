package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/** Same as {@link OnTickEvent}, except is only called while on the main menu. */
@SuppressWarnings("unused")
public class OnFETickEvent implements LuaEvent {

    /**
     * @param ticks always {@code 0}.
     */
    public OnFETickEvent(Double ticks) {}
}
