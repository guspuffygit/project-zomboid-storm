package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;

/**
 * Dispatched server-side from {@link io.pzstorm.storm.transfer.StormTransferHandler} the moment
 * {@code container.setHasBeenLooted(true)} flips for the source container of a Storm-mediated
 * transfer — i.e. a player just removed an item from the container.
 *
 * <p>Storm's transfer handler covers player inventory, bags, world object containers, and vehicle
 * part containers. Floor drops and dead body containers go through the vanilla {@code
 * RemoveInventoryItemFromContainerPacket} path and are NOT covered by this event.
 *
 * <p>Subscribers that care only about world loot containers should filter on {@code
 * container.getSourceGrid() != null} and exclude {@link zombie.iso.objects.IsoThumpable} parents.
 */
@RequiredArgsConstructor
public class OnContainerLootedEvent implements ZomboidEvent {

    @Getter private final IsoPlayer player;
    @Getter private final ItemContainer container;
    @Getter private final InventoryItem item;

    @Override
    public String getName() {
        return "OnContainerLooted";
    }
}
