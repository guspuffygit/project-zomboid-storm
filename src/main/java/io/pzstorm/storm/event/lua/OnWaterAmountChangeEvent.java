package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.iso.IsoObject;

/** Triggered when water amount changes for water containers. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnWaterAmountChangeEvent implements LuaEvent {

    /** Water container being updated. */
    public final IsoObject container;

    /** Amount of water before update. */
    public final Float oldWaterAmount;

    public OnWaterAmountChangeEvent(IsoObject container, Float oldWaterAmount) {
        this.container = container;
        this.oldWaterAmount = oldWaterAmount;
    }
}
