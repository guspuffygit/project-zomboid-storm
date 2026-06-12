package io.pzstorm.storm.transfer;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.OnClientCommand;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnContainerLootedEvent;
import io.pzstorm.storm.event.zomboid.OnItemTransferCompletedEvent;
import io.pzstorm.storm.metrics.TransferMetrics;
import io.pzstorm.storm.transfer.commands.CancelTransferCommand;
import io.pzstorm.storm.transfer.commands.TransferItemCommand;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameTime;
import zombie.Lua.LuaEventManager;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.random.Rand;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Clothing;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.Food;
import zombie.inventory.types.InventoryContainer;
import zombie.inventory.types.Radio;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoRadio;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.radio.devices.DeviceData;
import zombie.scripting.objects.CharacterTrait;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleManager;
import zombie.vehicles.VehiclePart;

/**
 * Server-side handler for Storm's UUID-based item transfer system. Receives per-item transfer
 * requests from clients via {@code sendClientCommand} and processes them with server-side timing,
 * bypassing the vanilla {@code Transaction} byte-ID system and its associated bugs (ID collisions,
 * vacuous truth on ID 0).
 *
 * <p>Handles transfers between player inventory, bags, world object containers, vehicle part
 * containers, and the floor (both directions: drops and pickups). Dead body containers and corpse
 * items without a world object continue to use the vanilla transaction system.
 */
public class StormTransferHandler {

    private static final String MODULE = "StormTransfer";

    /**
     * Maximum 2D distance (in tiles) between the player and a floor item's square for a pickup to
     * be accepted. The client's floor container aggregates a 3&times;3 square radius around the
     * player, so anything legitimate is within ~1.5 tiles; the margin covers client/server position
     * skew. Vanilla has no up-front check (only a post-hoc 1.1-tile rejection after the move).
     */
    private static final float PICKUP_MAX_DISTANCE = 3.0F;

    /**
     * Maximum 2D distance (in tiles) between the player and a vehicle whose part container is a
     * transfer leg. Matches the vanilla {@code TransactionManager.isConsistent()} check.
     */
    private static final float VEHICLE_MAX_DISTANCE = 5.0F;

    private static final ConcurrentHashMap<String, PendingTransfer> pendingTransfers =
            new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<IsoPlayer, LightweightPickupData> lightweightPickups =
            new ConcurrentHashMap<>();

    /**
     * One side of a transfer. Container legs wrap a resolved {@link ItemContainer}; floor legs
     * carry enough information to (re-)resolve the world position, since the floor is not a real
     * container on the server.
     */
    sealed interface Leg permits ContainerLeg, FloorPickupLeg, FloorDropLeg {}

    record ContainerLeg(ItemContainer container) implements Leg {}

    /**
     * Source leg for picking an item up off the ground. Identified by square coordinates plus the
     * inventory item id, and re-resolved at execution time &mdash; the world object can disappear
     * mid-timer (another player grabs it, chunk unloads).
     */
    record FloorPickupLeg(int x, int y, int z, int itemId) implements Leg {}

    /**
     * Destination leg for dropping an item on the ground. The drop square is chosen server-side at
     * accept time from the player's authoritative position (matching vanilla, which picks the
     * square at packet-parse time and drops there unconditionally when the timer elapses).
     */
    record FloorDropLeg(IsoGridSquare square) implements Leg {}

    private record PendingTransfer(
            IsoPlayer player,
            int itemId,
            Leg src,
            Leg dest,
            String srcRef,
            String destRef,
            float itemWeight,
            long endTime,
            long acceptedAt,
            long durationMillis) {}

    /**
     * Per-player burst state replicating vanilla {@code TransactionManager.LightweightData}:
     * repeated floor pickups of the same light ({@code weight <= 0.1}) item type within a 2-second
     * window complete instantly, up to 19 items per burst.
     */
    private static final class LightweightPickupData {
        String lastItemFullType = "";
        int itemsCount;
        long lastPickupMillis;
    }

    /** Accessor for {@link TransferMetrics} to observe the pending map size at scrape time. */
    public static int pendingSize() {
        return pendingTransfers.size();
    }

    @OnClientCommand
    public static void onTransferItem(TransferItemCommand event) {
        IsoPlayer player = event.getPlayer();
        String uuid = event.getUuid();
        int itemId = event.getItemId();
        String srcRef = event.getSrcContainerRef();
        String destRef = event.getDestContainerRef();

        LOGGER.debug(
                "transferItem: player={} uuid={} itemId={} src={} dest={}",
                player.getUsername(),
                uuid,
                itemId,
                srcRef,
                destRef);

        Leg src = resolveSourceLeg(srcRef, player);
        if (src == null) {
            LOGGER.debug(
                    "transferItem: unresolvable source '{}' for {}, rejecting uuid={}",
                    srcRef,
                    player.getUsername(),
                    uuid);
            reject(player, uuid);
            return;
        }

        if (src instanceof FloorPickupLeg pickup) {
            if (pickup.itemId() != itemId) {
                LOGGER.debug(
                        "transferItem: floorItem ref id {} != itemId {} for {}, rejecting uuid={}",
                        pickup.itemId(),
                        itemId,
                        player.getUsername(),
                        uuid);
                reject(player, uuid);
                return;
            }
            if (!isNearSquare(player, pickup.x(), pickup.y(), pickup.z())) {
                LOGGER.debug(
                        "transferItem: player {} too far from floor item at {},{},{} uuid={}",
                        player.getUsername(),
                        pickup.x(),
                        pickup.y(),
                        pickup.z(),
                        uuid);
                reject(player, uuid);
                return;
            }
        }

        InventoryItem item = findSourceItem(src, itemId);
        if (item == null) {
            LOGGER.debug(
                    "transferItem: item {} not found in source for {}",
                    itemId,
                    player.getUsername());
            reject(player, uuid);
            return;
        }

        // Reject if this item is already in a pending transfer for this player
        boolean alreadyPending =
                pendingTransfers.values().stream()
                        .anyMatch(pt -> pt.player == player && pt.itemId == itemId);
        if (alreadyPending) {
            LOGGER.debug(
                    "transferItem: item {} already pending for {}, rejecting uuid={}",
                    itemId,
                    player.getUsername(),
                    uuid);
            reject(player, uuid);
            return;
        }

        Leg dest = resolveDestLeg(destRef, player, item);
        if (dest == null) {
            LOGGER.debug(
                    "transferItem: unresolvable destination '{}' for {}, rejecting uuid={}",
                    destRef,
                    player.getUsername(),
                    uuid);
            reject(player, uuid);
            return;
        }

        if (src instanceof FloorPickupLeg && dest instanceof FloorDropLeg) {
            LOGGER.debug(
                    "transferItem: floor-to-floor transfer for {}, rejecting uuid={}",
                    player.getUsername(),
                    uuid);
            reject(player, uuid);
            return;
        }

        if (src instanceof ContainerLeg srcLeg
                && dest instanceof ContainerLeg destLeg
                && srcLeg.container() == destLeg.container()) {
            LOGGER.debug(
                    "transferItem: src == dest for {}, rejecting uuid={}",
                    player.getUsername(),
                    uuid);
            reject(player, uuid);
            return;
        }

        if (vehicleTooFar(src, player) || vehicleTooFar(dest, player)) {
            LOGGER.debug(
                    "transferItem: vehicle out of range for {}, rejecting uuid={}",
                    player.getUsername(),
                    uuid);
            reject(player, uuid);
            return;
        }

        if (dest instanceof ContainerLeg destLeg) {
            ItemContainer destContainer = destLeg.container();
            if (!destContainer.isItemAllowed(item)) {
                reject(player, uuid);
                return;
            }
            float pendingWeight = pendingWeightFor(destContainer);
            if (!hasRoomForWeight(
                    destContainer, player, item.getUnequippedWeight() + pendingWeight)) {
                reject(player, uuid);
                return;
            }
        }
        // FloorDropLeg destinations were validated by resolveDestLeg (square selection includes
        // floor capacity and the one-pending-drop-per-square rule).

        // Drops record the resolved square so event consumers see where the item landed.
        String resolvedDestRef =
                dest instanceof FloorDropLeg drop ? floorRef(drop.square()) : destRef;

        long duration = calculateDuration(item, src, dest, player);
        long endTime = GameTime.getServerTimeMills() + duration;

        pendingTransfers.put(
                uuid,
                new PendingTransfer(
                        player,
                        itemId,
                        src,
                        dest,
                        srcRef,
                        resolvedDestRef,
                        item.getUnequippedWeight(),
                        endTime,
                        System.nanoTime(),
                        duration));

        LOGGER.debug(
                "transferItem: accepted uuid={} duration={}ms for {}",
                uuid,
                duration,
                player.getUsername());
        TransferMetrics.recordAccepted();
        sendAccepted(player, uuid, duration);
    }

    @OnClientCommand
    public static void onCancelTransfer(CancelTransferCommand event) {
        String uuid = event.getUuid();
        PendingTransfer pending = pendingTransfers.get(uuid);
        if (pending != null && pending.player == event.getPlayer()) {
            pendingTransfers.remove(uuid);
            TransferMetrics.recordCancelled();
            LOGGER.debug(
                    "cancelTransfer: removed uuid={} for {}",
                    uuid,
                    event.getPlayer().getUsername());
        }
    }

    /**
     * Processes pending transfers whose timers have elapsed. Called every server tick from {@link
     * io.pzstorm.storm.patch.fixes.TransactionManagerPatch.UpdateAdvice}.
     */
    public static void processPending() {
        if (!GameServer.server) return;
        if (pendingTransfers.isEmpty()) return;

        long now = GameTime.getServerTimeMills();
        Iterator<Map.Entry<String, PendingTransfer>> it = pendingTransfers.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, PendingTransfer> entry = it.next();
            String uuid = entry.getKey();
            PendingTransfer p = entry.getValue();

            if (p.endTime > now) continue;

            it.remove();
            executeTransfer(uuid, p);
        }
    }

    private static void executeTransfer(String uuid, PendingTransfer p) {
        if (p.src instanceof FloorPickupLeg pickup) {
            executeFloorPickup(uuid, p, pickup);
            return;
        }

        ItemContainer src = ((ContainerLeg) p.src).container();

        // Re-validate before executing
        InventoryItem item = src.getItemWithID(p.itemId);
        if (item == null) {
            LOGGER.debug(
                    "processPending: item {} gone from source, rejecting uuid={}", p.itemId, uuid);
            reject(p.player, uuid);
            return;
        }

        if (p.dest instanceof FloorDropLeg drop) {
            executeFloorDrop(uuid, p, src, item, drop);
            return;
        }

        executeContainerTransfer(uuid, p, src, item, ((ContainerLeg) p.dest).container());
    }

    private static void executeContainerTransfer(
            String uuid,
            PendingTransfer p,
            ItemContainer src,
            InventoryItem item,
            ItemContainer dest) {
        if (!dest.isItemAllowed(item)
                || !hasRoomForWeight(dest, p.player, item.getUnequippedWeight())) {
            LOGGER.debug("processPending: dest can't accept item, rejecting uuid={}", uuid);
            reject(p.player, uuid);
            return;
        }

        if (vehicleTooFar(p.src, p.player) || vehicleTooFar(p.dest, p.player)) {
            LOGGER.debug("processPending: vehicle out of range, rejecting uuid={}", uuid);
            reject(p.player, uuid);
            return;
        }

        unequipIfCarried(p.player, src, item);

        // Mark source container as explored/looted (matches vanilla Transaction.update())
        src.setExplored(true);
        src.setHasBeenLooted(true);
        StormEventDispatcher.dispatchEvent(new OnContainerLootedEvent(p.player, src, item));

        updateItemState(p.player, item);

        // Execute the transfer
        src.Remove(item);
        dest.addItem(item);

        // Broadcast to relevant clients (same as Transaction.update())
        GameServer.sendRemoveItemFromContainer(src, item);
        GameServer.sendAddItemToContainer(dest, item);

        LOGGER.debug(
                "processPending: moved item {} for {} uuid={}",
                item.getFullType(),
                p.player.getUsername(),
                uuid);
        complete(uuid, p, item);
    }

    /**
     * Executes a floor pickup: removes the item's world object from the map (broadcasting {@code
     * RemoveItemFromSquare}, which also cleans up a paired {@code IsoRadio} object) and adds the
     * item to the destination container. Mirrors the vanilla {@code Transaction.update()}
     * world-object branch; like vanilla, no chef/wetness item-state updates run on this path, only
     * {@code flushWetness} for clothing.
     */
    private static void executeFloorPickup(String uuid, PendingTransfer p, FloorPickupLeg pickup) {
        IsoWorldInventoryObject worldObj = findWorldItem(pickup);
        if (worldObj == null || worldObj.getSquare() == null) {
            LOGGER.debug(
                    "processPending: floor item {} gone from {},{},{}, rejecting uuid={}",
                    pickup.itemId(),
                    pickup.x(),
                    pickup.y(),
                    pickup.z(),
                    uuid);
            reject(p.player, uuid);
            return;
        }

        InventoryItem item = worldObj.getItem();
        ItemContainer dest = ((ContainerLeg) p.dest).container();

        if (!dest.isItemAllowed(item)
                || !hasRoomForWeight(dest, p.player, item.getUnequippedWeight())) {
            LOGGER.debug("processPending: dest can't accept floor item, rejecting uuid={}", uuid);
            reject(p.player, uuid);
            return;
        }

        if (vehicleTooFar(p.dest, p.player)) {
            LOGGER.debug("processPending: vehicle out of range, rejecting uuid={}", uuid);
            reject(p.player, uuid);
            return;
        }

        if (item instanceof Clothing clothing) {
            clothing.flushWetness();
        }

        GameServer.RemoveItemFromMap(worldObj);
        item.setWorldItem(null);
        dest.addItem(item);
        GameServer.sendAddItemToContainer(dest, item);

        recordLightweightPickup(p.player, item);

        LOGGER.debug(
                "processPending: picked up floor item {} for {} uuid={}",
                item.getFullType(),
                p.player.getUsername(),
                uuid);
        complete(uuid, p, item);
    }

    /**
     * Executes a floor drop: removes the item from the source container and places it on the drop
     * square chosen at accept time. {@code AddWorldInventoryItem} broadcasts {@code AddItemToMap}
     * to all relevant clients and internally handles corpse items (spawns a dead body), animal
     * items (spawns the animal), and generators &mdash; the same call the vanilla server-side Lua
     * {@code dropOnFloor} path makes.
     */
    private static void executeFloorDrop(
            String uuid,
            PendingTransfer p,
            ItemContainer src,
            InventoryItem item,
            FloorDropLeg drop) {
        IsoGridSquare dropSquare = drop.square();

        unequipIfCarried(p.player, src, item);

        // Mark source container as explored/looted (matches vanilla Transaction.update())
        src.setExplored(true);
        src.setHasBeenLooted(true);
        StormEventDispatcher.dispatchEvent(new OnContainerLootedEvent(p.player, src, item));

        updateItemState(p.player, item);

        src.Remove(item);
        GameServer.sendRemoveItemFromContainer(src, item);

        // Drop offsets within the square (port of ISTransferAction.GetDropItemOffset)
        float dropX = Rand.Next(0.0F, 1.0F);
        float dropY = Rand.Next(0.0F, 1.0F);
        float dropZ = dropSquare.getApparentZ(dropX, dropY) - dropSquare.getZ();
        if (p.player.isSeatedInVehicle()) {
            dropZ = (float) Math.floor(p.player.getZ());
        }
        if (Core.getInstance().getOptionDropItemsOnSquareCenter()) {
            dropX = Rand.Next(3, 7) / 10.0F;
            dropY = Rand.Next(3, 7) / 10.0F;
            dropZ = dropSquare.getApparentZ(dropX, dropY) - dropSquare.getZ();
        }

        dropSquare.AddWorldInventoryItem(item, dropX, dropY, dropZ);

        if (item instanceof Radio radio) {
            spawnPairedRadioObject(radio, dropSquare);
        }

        LOGGER.debug(
                "processPending: dropped item {} at {},{},{} for {} uuid={}",
                item.getFullType(),
                dropSquare.getX(),
                dropSquare.getY(),
                dropSquare.getZ(),
                p.player.getUsername(),
                uuid);
        complete(uuid, p, item);
    }

    private static void complete(String uuid, PendingTransfer p, InventoryItem item) {
        TransferMetrics.recordDone(p.acceptedAt);
        sendResult(p.player, uuid, "done", 1);
        StormEventDispatcher.dispatchEvent(
                new OnItemTransferCompletedEvent(
                        p.player, item, p.srcRef, p.destRef, uuid, p.durationMillis));
    }

    private static void reject(IsoPlayer player, String uuid) {
        TransferMetrics.recordRejected();
        sendResult(player, uuid, "rejected", 0);
    }

    /**
     * Unequips the item if it is worn, held, or attached on the player (matches vanilla {@code
     * Transaction.update()}).
     */
    private static void unequipIfCarried(IsoPlayer player, ItemContainer src, InventoryItem item) {
        if (src == player.getInventory() || src.isInCharacterInventory(player)) {
            player.removeAttachedItem(item);
            if (player.isEquipped(item)) {
                player.removeFromHands(item);
                player.removeWornItem(item, false);
                LuaEventManager.triggerEvent("OnClothingUpdated", player);
                player.updateHandEquips();
                GameServer.sendSyncClothing(player, null, item);
            }
        }
    }

    /** Item state updates matching vanilla {@code Transaction.update()} lines 156-172. */
    private static void updateItemState(IsoPlayer player, InventoryItem item) {
        boolean isReplacedOnCooked = false;
        if (item instanceof Food food) {
            food.setChef(player.getUsername());
            isReplacedOnCooked = food.getReplaceOnCooked() != null;
        }
        if (item instanceof DrainableComboItem drainable) {
            isReplacedOnCooked = drainable.getReplaceOnCooked() != null;
        }
        if (!isReplacedOnCooked) {
            item.update();
        }
        if (item instanceof Clothing clothing) {
            clothing.updateWetness();
        }
    }

    /**
     * Spawns the paired {@code IsoRadio} world object for a dropped radio item, mirroring the
     * server-side Lua {@code ISTransferAction:transferItem} radio block. The {@code RadioItemID}
     * modData entry must be a {@code Double} &mdash; {@code RemoveItemFromSquarePacket} matches it
     * with {@code instanceof Double} on pickup.
     */
    private static void spawnPairedRadioObject(Radio radioItem, IsoGridSquare dropSquare) {
        IsoRadio radioObj = new IsoRadio(IsoWorld.instance.getCell(), dropSquare, null);
        DeviceData deviceData = radioItem.getDeviceData();
        if (deviceData != null) {
            radioObj.setDeviceData(deviceData);
        }
        radioObj.getModData().rawset("RadioItemID", (double) radioItem.getID());
        dropSquare.AddSpecialObject(radioObj, dropSquare.getObjects().size());
        radioObj.transmitModData();
        LuaEventManager.triggerEvent("OnObjectAdded", radioObj);
        dropSquare.RecalcProperties();
        dropSquare.RecalcAllWithNeighbours(true);
    }

    /**
     * Cancels all pending transfers for a player. Called when a player disconnects to prevent
     * orphaned transfers.
     */
    public static void cancelAllForPlayer(IsoPlayer player) {
        lightweightPickups.remove(player);
        pendingTransfers
                .entrySet()
                .removeIf(
                        entry -> {
                            if (entry.getValue().player == player) {
                                LOGGER.debug(
                                        "cancelAllForPlayer: removing uuid={} for {}",
                                        entry.getKey(),
                                        player.getUsername());
                                TransferMetrics.recordCancelled();
                                return true;
                            }
                            return false;
                        });
    }

    // ------------------------------------------------------------------
    // Leg resolution
    // ------------------------------------------------------------------

    private static Leg resolveSourceLeg(String ref, IsoPlayer player) {
        if (ref == null) return null;

        if (ref.startsWith("floorItem:")) {
            try {
                String[] parts = ref.split(":");
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                int itemId = Integer.parseInt(parts[4]);
                return new FloorPickupLeg(x, y, z, itemId);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                LOGGER.warn("resolveSourceLeg: bad floorItem ref '{}'", ref);
                return null;
            }
        }

        ItemContainer container = resolveContainer(ref, player);
        return container == null ? null : new ContainerLeg(container);
    }

    private static Leg resolveDestLeg(String ref, IsoPlayer player, InventoryItem item) {
        if (ref == null) return null;

        if ("floor".equals(ref)) {
            IsoGridSquare dropSquare = getNotFullFloorSquare(player, item);
            if (dropSquare == null) {
                LOGGER.debug(
                        "resolveDestLeg: no floor square with room near {}", player.getUsername());
                return null;
            }
            return new FloorDropLeg(dropSquare);
        }

        ItemContainer container = resolveContainer(ref, player);
        return container == null ? null : new ContainerLeg(container);
    }

    private static InventoryItem findSourceItem(Leg src, int itemId) {
        if (src instanceof FloorPickupLeg pickup) {
            IsoWorldInventoryObject worldObj = findWorldItem(pickup);
            return worldObj == null ? null : worldObj.getItem();
        }
        return ((ContainerLeg) src).container().getItemWithID(itemId);
    }

    /**
     * Finds the world object for a floor pickup by scanning the square's world objects for a
     * matching inventory item id (id-based, unlike vanilla's fragile index-based {@code
     * RemoveItemFromSquare} addressing).
     */
    private static IsoWorldInventoryObject findWorldItem(FloorPickupLeg pickup) {
        IsoGridSquare sq = getSquare(pickup.x(), pickup.y(), pickup.z());
        if (sq == null) return null;

        for (int i = 0; i < sq.getWorldObjects().size(); i++) {
            IsoWorldInventoryObject worldObj = sq.getWorldObjects().get(i);
            if (worldObj != null
                    && worldObj.getItem() != null
                    && worldObj.getItem().getID() == pickup.itemId()) {
                return worldObj;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Floor drop square selection (port of vanilla TransactionManager)
    // ------------------------------------------------------------------

    /**
     * Picks the square to drop on: the player's current square if it qualifies, otherwise the first
     * qualifying square in the surrounding 3&times;3 area. Returns {@code null} if no square has
     * room (the client's {@code isValid()} ran the same check, so this only fails on client/server
     * state skew).
     */
    private static IsoGridSquare getNotFullFloorSquare(IsoPlayer player, InventoryItem item) {
        IsoGridSquare current = player.getCurrentSquare();
        if (current == null) return null;

        // Synthetic floor container supplies the vanilla floor capacity model
        ItemContainer floorContainer = new ItemContainer("floor", current, null);

        if (canDropOnFloor(current, player)
                && floorHasRoomFor(current, player, item, floorContainer)) {
            return current;
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                IsoGridSquare square =
                        getSquare(
                                (int) (player.getX() + dx),
                                (int) (player.getY() + dy),
                                (int) player.getZ());
                if (canDropOnFloor(square, player)
                        && floorHasRoomFor(square, player, item, floorContainer)) {
                    return square;
                }
            }
        }
        return null;
    }

    private static boolean canDropOnFloor(IsoGridSquare square, IsoPlayer player) {
        if (square == null) return false;
        if (!square.TreatAsSolidFloor()) return false;
        if (square.isSolid() || square.isSolidTrans()) return false;

        IsoGridSquare current = player.getCurrentSquare();
        if (current != null && square != current) {
            if (current.isBlockedTo(square) || current.isWindowTo(square)) return false;
            if (current.HasStairs() != square.HasStairs()) return false;
            if (current.HasStairs()
                    && !current.isSameStaircase(square.getX(), square.getY(), square.getZ())) {
                return false;
            }
        }
        return true;
    }

    private static boolean floorHasRoomFor(
            IsoGridSquare square,
            IsoPlayer player,
            InventoryItem item,
            ItemContainer floorContainer) {
        float capacity = floorContainer.getEffectiveCapacity(player);
        float totalWeight = square.getTotalWeightOfItemsOnFloor();
        if (item.isOnGroundOrInsideBagOnSquare(square)) {
            totalWeight -= item.getUnequippedWeight();
        }
        if (totalWeight >= capacity) {
            return false;
        }
        // Vanilla allows only one in-flight drop per square, fanning bursts out to neighbours
        if (hasPendingDropOn(square)) {
            return false;
        }
        if (ItemContainer.floatingPointCorrection(totalWeight) + item.getUnequippedWeight()
                <= capacity) {
            return true;
        }
        // Hack for single items heavier than the floor capacity (matches vanilla)
        return item.getUnequippedWeight() >= capacity;
    }

    private static boolean hasPendingDropOn(IsoGridSquare square) {
        return pendingTransfers.values().stream()
                .anyMatch(pt -> pt.dest instanceof FloorDropLeg drop && drop.square() == square);
    }

    // ------------------------------------------------------------------
    // Validation helpers
    // ------------------------------------------------------------------

    private static boolean isNearSquare(IsoPlayer player, int x, int y, int z) {
        if (Math.abs((int) Math.floor(player.getZ()) - z) > 1) {
            return false;
        }
        return IsoUtils.DistanceTo(player.getX(), player.getY(), x + 0.5F, y + 0.5F)
                <= PICKUP_MAX_DISTANCE;
    }

    private static boolean vehicleTooFar(Leg leg, IsoPlayer player) {
        if (leg instanceof ContainerLeg containerLeg
                && containerLeg.container().getParent() instanceof BaseVehicle vehicle) {
            return IsoUtils.DistanceTo(player.getX(), player.getY(), vehicle.getX(), vehicle.getY())
                    > VEHICLE_MAX_DISTANCE;
        }
        return false;
    }

    private static float pendingWeightFor(ItemContainer dest) {
        return (float)
                pendingTransfers.values().stream()
                        .filter(
                                pt ->
                                        pt.dest instanceof ContainerLeg containerLeg
                                                && containerLeg.container() == dest)
                        .mapToDouble(pt -> pt.itemWeight)
                        .sum();
    }

    private static String floorRef(IsoGridSquare square) {
        return "floor:" + square.getX() + ":" + square.getY() + ":" + square.getZ();
    }

    // ------------------------------------------------------------------
    // Container ref resolution
    // ------------------------------------------------------------------

    /**
     * Resolves a container from a reference string sent by the client. Reference formats:
     *
     * <ul>
     *   <li>{@code "player"} — player's main inventory
     *   <li>{@code "bag:<itemId>"} — InventoryContainer in player's inventory tree
     *   <li>{@code "object:<x>:<y>:<z>:<objectIndex>:<containerIndex>"} — world object container
     *   <li>{@code "worlditem:<x>:<y>:<z>:<objectIndex>"} — placed container on the ground
     *       (InventoryContainer placed as IsoWorldInventoryObject)
     *   <li>{@code "vehicle:<vehicleId>:<partIndex>"} — vehicle part container
     * </ul>
     *
     * <p>Floor legs ({@code "floor"}, {@code "floorItem:<x>:<y>:<z>:<itemId>"}) are not containers
     * and are resolved by {@link #resolveSourceLeg} / {@link #resolveDestLeg} instead.
     *
     * @return the resolved container, or {@code null} if not found.
     */
    static ItemContainer resolveContainer(String ref, IsoPlayer player) {
        if (ref == null) return null;

        if ("player".equals(ref)) {
            return player.getInventory();
        }

        if (ref.startsWith("bag:")) {
            return resolveBag(ref, player);
        }

        if (ref.startsWith("object:")) {
            return resolveWorldObject(ref);
        }

        if (ref.startsWith("worlditem:")) {
            return resolveWorldItem(ref);
        }

        if (ref.startsWith("vehicle:")) {
            return resolveVehicle(ref);
        }

        LOGGER.warn("resolveContainer: unknown ref format '{}', rejecting transfer", ref);
        return null;
    }

    private static ItemContainer resolveBag(String ref, IsoPlayer player) {
        try {
            int itemId = Integer.parseInt(ref.substring("bag:".length()));
            InventoryItem item = player.getInventory().getItemWithIDRecursiv(itemId);
            if (item instanceof InventoryContainer bag) {
                return bag.getItemContainer();
            }
        } catch (NumberFormatException e) {
            LOGGER.warn("resolveContainer: bad bag ref '{}'", ref);
        }
        return null;
    }

    private static ItemContainer resolveWorldObject(String ref) {
        try {
            String[] parts = ref.split(":");
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            int objectIndex = Integer.parseInt(parts[4]);
            int containerIndex = Integer.parseInt(parts[5]);

            IsoGridSquare sq = getSquare(x, y, z);
            if (sq == null) return null;

            if (objectIndex < 0 || objectIndex >= sq.getObjects().size()) return null;
            IsoObject obj = sq.getObjects().get(objectIndex);
            if (obj == null) return null;

            return obj.getContainerByIndex(containerIndex);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            LOGGER.warn("resolveContainer: bad object ref '{}'", ref);
        }
        return null;
    }

    private static ItemContainer resolveWorldItem(String ref) {
        try {
            String[] parts = ref.split(":");
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            int objectIndex = Integer.parseInt(parts[4]);

            IsoGridSquare sq = getSquare(x, y, z);
            if (sq == null) return null;

            if (objectIndex < 0 || objectIndex >= sq.getObjects().size()) return null;
            IsoObject obj = sq.getObjects().get(objectIndex);

            if (obj instanceof IsoWorldInventoryObject worldObj) {
                InventoryItem item = worldObj.getItem();
                if (item instanceof InventoryContainer bag) {
                    return bag.getItemContainer();
                }
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            LOGGER.warn("resolveContainer: bad worlditem ref '{}'", ref);
        }
        return null;
    }

    private static ItemContainer resolveVehicle(String ref) {
        try {
            String[] parts = ref.split(":");
            short vehicleId = Short.parseShort(parts[1]);
            int partIndex = Integer.parseInt(parts[2]);

            BaseVehicle vehicle = VehicleManager.instance.getVehicleByID(vehicleId);
            if (vehicle == null) return null;

            VehiclePart part = vehicle.getPartByIndex(partIndex);
            if (part == null) return null;

            return part.getItemContainer();
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            LOGGER.warn("resolveContainer: bad vehicle ref '{}'", ref);
        }
        return null;
    }

    private static IsoGridSquare getSquare(int x, int y, int z) {
        IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
        if (sq == null && GameServer.server) {
            sq = ServerMap.instance.getGridSquare(x, y, z);
        }
        return sq;
    }

    private static boolean hasRoomForWeight(
            ItemContainer dest, IsoPlayer player, float totalWeight) {
        float capacity = dest.getEffectiveCapacity(player);
        float currentWeight = dest.getCapacityWeight();
        return ItemContainer.floatingPointCorrection(currentWeight) + totalWeight <= capacity;
    }

    // ------------------------------------------------------------------
    // Duration
    // ------------------------------------------------------------------

    /**
     * Calculates transfer duration in milliseconds, replicating {@code Transaction.getDuration()}.
     *
     * <p>The vanilla method multiplies by 20 at the end (converting from Lua-scale time to server
     * millis). The client divides by 20 when it receives the duration to get Lua-scale time for the
     * progress bar. We store the raw millis value (i.e. the vanilla result as-is) and send the
     * Lua-scale value (divided by 20) to the client.
     */
    static long calculateDuration(InventoryItem item, Leg srcLeg, Leg destLeg, IsoPlayer player) {
        if (srcLeg instanceof FloorPickupLeg) {
            return calculateFloorPickupDuration(item, destLeg, player);
        }

        ItemContainer src = ((ContainerLeg) srcLeg).container();
        boolean srcMainInventory = src == player.getInventory();
        boolean srcInInventory = srcMainInventory || src.isInCharacterInventory(player);
        boolean destFloor = destLeg instanceof FloorDropLeg;

        float maxTime = 120.0f;
        float destCapacityDelta = 1.0f;

        if (destFloor) {
            // Vanilla: only the main inventory gets the fast base time for drops
            if (srcMainInventory) {
                maxTime = 50.0f;
            }
        } else {
            ItemContainer dest = ((ContainerLeg) destLeg).container();
            boolean destInInventory =
                    dest == player.getInventory() || dest.isInCharacterInventory(player);

            if (srcInInventory && destInInventory) {
                // Inventory-to-inventory (existing logic)
                if (srcMainInventory && dest.isInCharacterInventory(player)) {
                    // Packing: inventory -> bag in inventory
                    destCapacityDelta = dest.getCapacityWeight() / dest.getMaxWeight();
                }
            } else if (!srcInInventory && destInInventory) {
                // External -> player inventory
                maxTime = 50.0f;
            } else if (srcInInventory && !destInInventory) {
                // Player inventory -> external
                maxTime = 50.0f;
            }
            // External -> external: base 120
        }

        if (destCapacityDelta < 0.4f) {
            destCapacityDelta = 0.4f;
        }

        float w = item.getActualWeight();
        if (w > 3.0f) {
            w = 3.0f;
        }
        maxTime = maxTime * w * destCapacityDelta;

        if (Core.getInstance().getGameMode().equals("LastStand")) {
            maxTime *= 0.3f;
        }

        if (destFloor) {
            // Vanilla floor multipliers: applied after LastStand, before traits
            if (srcMainInventory) {
                maxTime *= 0.1f;
            } else if (!srcInInventory) {
                maxTime *= 0.2f;
            }
            // Bag in inventory -> floor: no multiplier (vanilla "Unpack -> drop")
        }

        maxTime = applyTraitMultipliers(maxTime, player);

        // Vanilla multiplies by 20 to convert Lua-scale time to server millis
        return (long) (maxTime * 20.0f);
    }

    /**
     * Floor pickups in vanilla carry {@code itemId == -1}, so {@code Transaction.getDuration()}
     * never finds the item and skips weight scaling: the base time depends only on the destination.
     * Light items (≤ 0.1 weight) additionally get the vanilla burst fast-path.
     */
    private static long calculateFloorPickupDuration(
            InventoryItem item, Leg destLeg, IsoPlayer player) {
        ItemContainer dest = ((ContainerLeg) destLeg).container();
        boolean destInInventory =
                dest == player.getInventory() || dest.isInCharacterInventory(player);

        float maxTime = destInInventory ? 50.0f : 120.0f;

        if (Core.getInstance().getGameMode().equals("LastStand")) {
            maxTime *= 0.3f;
        }

        maxTime = applyTraitMultipliers(maxTime, player);

        if (item.getWeight() <= 0.1f) {
            LightweightPickupData data =
                    lightweightPickups.computeIfAbsent(player, p -> new LightweightPickupData());
            long now = System.currentTimeMillis();
            if (data.lastPickupMillis == 0L || now - data.lastPickupMillis < 2000L) {
                if (data.itemsCount < 19 && data.lastItemFullType.equals(item.getFullType())) {
                    maxTime = 0.0f;
                    data.itemsCount++;
                } else {
                    data.itemsCount = 0;
                    data.lastItemFullType = "";
                }
            }
        }

        return (long) (maxTime * 20.0f);
    }

    private static float applyTraitMultipliers(float maxTime, IsoPlayer player) {
        if (player.hasTrait(CharacterTrait.DEXTROUS)) {
            maxTime *= 0.5f;
        }
        if (player.hasTrait(CharacterTrait.ALL_THUMBS) || player.isWearingAwkwardGloves()) {
            maxTime *= 2.0f;
        }
        return maxTime;
    }

    /** Burst bookkeeping matching vanilla {@code Transaction.update()} lightweight data updates. */
    private static void recordLightweightPickup(IsoPlayer player, InventoryItem item) {
        LightweightPickupData data =
                lightweightPickups.computeIfAbsent(player, p -> new LightweightPickupData());
        String fullType = item.getFullType() == null ? "" : item.getFullType();
        if (!data.lastItemFullType.equals(fullType)) {
            data.itemsCount = 0;
        }
        data.lastItemFullType = fullType;
        data.lastPickupMillis = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    // Client responses
    // ------------------------------------------------------------------

    private static void sendAccepted(IsoPlayer player, String uuid, long durationMillis) {
        KahluaTable args = zombie.Lua.LuaManager.platform.newTable();
        args.rawset("uuid", uuid);
        args.rawset("status", "accepted");
        // Send Lua-scale duration (millis / 20) to match vanilla's getItemTransactionDuration
        args.rawset("duration", (double) (durationMillis / 20));
        GameServer.sendServerCommand(player, MODULE, "result", args);
    }

    private static void sendResult(IsoPlayer player, String uuid, String status, int count) {
        KahluaTable args = zombie.Lua.LuaManager.platform.newTable();
        args.rawset("uuid", uuid);
        args.rawset("status", status);
        args.rawset("count", (double) count);
        GameServer.sendServerCommand(player, MODULE, "result", args);
    }
}
