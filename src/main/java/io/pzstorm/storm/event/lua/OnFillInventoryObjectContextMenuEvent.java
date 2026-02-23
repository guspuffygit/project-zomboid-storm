package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import se.krka.kahlua.vm.KahluaTable;

/**
 * Triggered when a player right clicks an inventory item. Use this event to add items to context
 * menu without mod conflicts.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnFillInventoryObjectContextMenuEvent implements LuaEvent {

    /** Index of player that triggered the event. */
    public final Double playerIndex;

    // TODO: finish documenting this event
    public final KahluaTable context, items;

    public OnFillInventoryObjectContextMenuEvent(
            Double playerIndex, KahluaTable context, KahluaTable items) {
        this.playerIndex = playerIndex;
        this.context = context;
        this.items = items;
    }
}
