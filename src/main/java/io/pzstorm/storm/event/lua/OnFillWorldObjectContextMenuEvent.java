package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import se.krka.kahlua.vm.KahluaTable;

/**
 * Triggered when a player right clicks a world item. Use the event to add items to context menu
 * without mod conflicts.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnFillWorldObjectContextMenuEvent implements LuaEvent {

    /** Index of player that triggered the event. */
    public final Double playerIndex;

    // TODO: finish documenting this event
    public final KahluaTable context, worldObjects;
    public final Boolean isTest;

    public OnFillWorldObjectContextMenuEvent(
            Double arg1, KahluaTable context, KahluaTable worldObjects, Boolean isTest) {
        this.playerIndex = arg1;
        this.context = context;
        this.worldObjects = worldObjects;
        this.isTest = isTest;
    }
}
