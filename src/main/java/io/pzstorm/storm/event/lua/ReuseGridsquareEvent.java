package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.iso.IsoGridSquare;

@SuppressWarnings({"WeakerAccess", "unused"})
public class ReuseGridsquareEvent implements LuaEvent {

    // TODO: document this event
    public final IsoGridSquare gridSquare;

    public ReuseGridsquareEvent(IsoGridSquare gridSquare) {
        this.gridSquare = gridSquare;
    }
}
