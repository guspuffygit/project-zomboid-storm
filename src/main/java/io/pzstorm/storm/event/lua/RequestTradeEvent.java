package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.characters.IsoPlayer;

@SuppressWarnings({"WeakerAccess", "unused"})
public class RequestTradeEvent implements LuaEvent {

    // TODO: document this event
    public final IsoPlayer player;

    public RequestTradeEvent(IsoPlayer player) {
        this.player = player;
    }
}
