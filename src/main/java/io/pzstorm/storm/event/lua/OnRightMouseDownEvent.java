package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/** Triggered when right mouse button is down. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnRightMouseDownEvent implements LuaEvent {

    /** Position of mouse along x-axis. */
    public final Double x;

    /** Position of mouse along y-axis. */
    public final Double y;

    public OnRightMouseDownEvent(Double x, Double y) {
        this.x = x;
        this.y = y;
    }
}
