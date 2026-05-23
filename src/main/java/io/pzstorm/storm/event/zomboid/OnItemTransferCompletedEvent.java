package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;

/**
 * Dispatched server-side from {@link io.pzstorm.storm.transfer.StormTransferHandler} after a
 * UUID-keyed item transfer has fully executed (item removed from source, added to destination, and
 * sync packets broadcast). Not fired for rejected or cancelled transfers.
 *
 * <p>{@link #srcRef} and {@link #destRef} are the Storm container reference strings that the client
 * originally sent ({@code "player"}, {@code "bag:<id>"}, {@code
 * "object:<x>:<y>:<z>:<objIdx>:<containerIdx>"}, {@code "worlditem:<x>:<y>:<z>:<objIdx>"}, {@code
 * "vehicle:<vid>:<partIdx>"}). Subscribers can filter on the prefix to scope to particular
 * container kinds (e.g. world containers only).
 */
@RequiredArgsConstructor
public class OnItemTransferCompletedEvent implements ZomboidEvent {

    @Getter private final IsoPlayer player;
    @Getter private final InventoryItem item;
    @Getter private final String srcRef;
    @Getter private final String destRef;
    @Getter private final String uuid;
    @Getter private final long durationMillis;

    @Override
    public String getName() {
        return "OnItemTransferCompleted";
    }
}
