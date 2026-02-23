package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.iso.IsoObject;

/**
 * Triggered when right mouse button clicked on {@link IsoObject}.
 *
 * @see OnObjectRightMouseButtonUpEvent
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnObjectRightMouseButtonDownEvent implements LuaEvent {

    /** Object clicked on. */
    public final IsoObject object;

    /** Position of mouse along x-axis. */
    public final Double x;

    /** Position of mouse along y-axis. */
    public final Double y;

    public OnObjectRightMouseButtonDownEvent(IsoObject object, Double x, Double y) {
        this.object = object;
        this.x = x;
        this.y = y;
    }
}
