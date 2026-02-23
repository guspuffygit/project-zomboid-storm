package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/**
 * Triggered when mouse button is down.
 *
 * @see OnMouseMoveEvent
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnMouseDownEvent implements LuaEvent {

    /** Position of the mouse along x-axis. */
    public final Double x;

    /** Position of the mouse along y-axis. */
    public final Double y;

    public OnMouseDownEvent(Double x, Double y) {
        this.x = x;
        this.y = y;
    }
}
