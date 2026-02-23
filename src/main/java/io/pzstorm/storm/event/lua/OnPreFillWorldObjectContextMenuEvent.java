package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import se.krka.kahlua.vm.KahluaTable;

// TODO: finish documenting this event
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnPreFillWorldObjectContextMenuEvent implements LuaEvent {

    /** Index of player that triggered the event. */
    public final Double playerIndex;

    // TODO: finish documenting this event
    public final KahluaTable context, worldObjects;
    public final Boolean isTest;

    public OnPreFillWorldObjectContextMenuEvent(
            Double arg1, KahluaTable context, KahluaTable worldObjects, Boolean isTest) {
        this.playerIndex = arg1;
        this.context = context;
        this.worldObjects = worldObjects;
        this.isTest = isTest;
    }
}
