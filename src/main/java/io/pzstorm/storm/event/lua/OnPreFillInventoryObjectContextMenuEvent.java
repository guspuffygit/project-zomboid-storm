package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import se.krka.kahlua.vm.KahluaTable;

// TODO: finish documenting this event
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnPreFillInventoryObjectContextMenuEvent implements LuaEvent {

    /** Index of player that triggered the event. */
    public final Double playerIndex;

    public final KahluaTable context, items;

    public OnPreFillInventoryObjectContextMenuEvent(
            Double playerIndex, KahluaTable context, KahluaTable items) {
        this.playerIndex = playerIndex;
        this.context = context;
        this.items = items;
    }
}
