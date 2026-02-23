package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.iso.weather.ClimateManager;

/** Triggered every time {@link ClimateManager} is updated in debug mode. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnClimateTickDebugEvent implements LuaEvent {

    /** {@link ClimateManager} that is being updated. */
    public final ClimateManager manager;

    public OnClimateTickDebugEvent(ClimateManager manager) {
        this.manager = manager;
    }
}
