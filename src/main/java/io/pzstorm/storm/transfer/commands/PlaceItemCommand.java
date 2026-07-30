package io.pzstorm.storm.transfer.commands;

import io.pzstorm.storm.event.core.ClientCommand;
import io.pzstorm.storm.event.core.ClientCommandEvent;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;

/**
 * Request to place an inventory item as a world object on a specific square, sent by {@code
 * StormPlaceItemFix.lua} in place of the vanilla client-only {@code
 * ISDropWorldItemAction:complete()} placement.
 *
 * <p>{@link #getItemId()} returns {@code -1} when the field is missing so a malformed command is
 * rejected by the handler's normal item lookup rather than throwing.
 */
@ClientCommand(module = "StormTransfer", command = "placeItem")
public class PlaceItemCommand extends ClientCommandEvent {

    public PlaceItemCommand(IsoPlayer player, @Nullable KahluaTable args) {
        super(player, args);
    }

    public int getItemId() {
        return (int) number("itemId", -1.0);
    }

    public int getX() {
        return (int) number("x", 0.0);
    }

    public int getY() {
        return (int) number("y", 0.0);
    }

    public int getZ() {
        return (int) number("z", 0.0);
    }

    public float getXOffset() {
        return (float) number("xoffset", 0.0);
    }

    public float getYOffset() {
        return (float) number("yoffset", 0.0);
    }

    public float getZOffset() {
        return (float) number("zoffset", 0.0);
    }

    public float getRotation() {
        return (float) number("rotation", 0.0);
    }

    private double number(String key, double fallback) {
        Double value = getDouble(key);
        return value == null ? fallback : value;
    }
}
