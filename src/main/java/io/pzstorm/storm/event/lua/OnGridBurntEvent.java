package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.iso.IsoGridSquare;

/** Triggered when {@link IsoGridSquare} is burned by fire. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnGridBurntEvent implements LuaEvent {

    /** {@link IsoGridSquare} being burned by fire. */
    public final IsoGridSquare gridSquare;

    public OnGridBurntEvent(IsoGridSquare gridSquare) {
        this.gridSquare = gridSquare;
    }
}
