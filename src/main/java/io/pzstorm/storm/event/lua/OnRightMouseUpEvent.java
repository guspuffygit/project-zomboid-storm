package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/** Triggered when mouse button is released. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnRightMouseUpEvent implements LuaEvent {

    /** Position of mouse along x-axis. */
    public final Double x;

    /** Position of mouse along y-axis. */
    public final Double y;

    public OnRightMouseUpEvent(Double x, Double y) {
        this.x = x;
        this.y = y;
    }
}
