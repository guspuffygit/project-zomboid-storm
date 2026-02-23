package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.characters.IsoPlayer;

@SuppressWarnings({"WeakerAccess", "unused"})
public class AcceptedTradeEvent implements LuaEvent {

    // TODO: document this event
    public final IsoPlayer player;

    public AcceptedTradeEvent(IsoPlayer player) {
        this.player = player;
    }
}
