package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.characters.IsoGameCharacter;
import zombie.vehicles.VehiclePart;

/** Triggered when {@link VehiclePart} is damaged while being driven. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnVehicleDamageTextureEvent implements LuaEvent {

    /** Player driving the vehicle. */
    public final IsoGameCharacter driver;

    public OnVehicleDamageTextureEvent(IsoGameCharacter driver) {
        this.driver = driver;
    }
}
