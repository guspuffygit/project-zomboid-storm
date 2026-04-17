package io.pzstorm.storm.transfer;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.OnClientCommand;
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
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Clothing;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.Food;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.network.GameServer;
import zombie.network.ServerMap;
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
 * <p>Handles transfers between player inventory, bags, world object containers, and vehicle part
 * containers. Floor drops and dead body containers continue to use the vanilla transaction system.
 */
public class StormTransferHandler {

    private static final String MODULE = "StormTransfer";

    private static final ConcurrentHashMap<String, PendingTransfer> pendingTransfers =
            new ConcurrentHashMap<>();

    private record PendingTransfer(
            IsoPlayer player, int itemId, ItemContainer src, ItemContainer dest, long endTime) {}

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

        ItemContainer src = resolveContainer(srcRef, player);
        ItemContainer dest = resolveContainer(destRef, player);

        if (src == null || dest == null || src == dest) {
            LOGGER.debug(
                    "transferItem: rejected uuid={} for {} (src={}, dest={}, same={})",
                    uuid,
                    player.getUsername(),
                    src == null ? "null" : "ok",
                    dest == null ? "null" : "ok",
                    src == dest);
            sendResult(player, uuid, "rejected", 0);
            return;
        }

        InventoryItem item = src.getItemWithID(itemId);
        if (item == null) {
            LOGGER.debug(
                    "transferItem: item {} not found in source for {}",
                    itemId,
                    player.getUsername());
            sendResult(player, uuid, "rejected", 0);
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
            sendResult(player, uuid, "rejected", 0);
            return;
        }

        if (!dest.isItemAllowed(item)) {
            sendResult(player, uuid, "rejected", 0);
            return;
        }

        // Sum weight of items already queued to this destination
        float pendingWeight =
                (float)
                        pendingTransfers.values().stream()
                                .filter(pt -> pt.dest == dest)
                                .mapToDouble(
                                        pt -> {
                                            InventoryItem pendingItem =
                                                    pt.src.getItemWithID(pt.itemId);
                                            return pendingItem != null
                                                    ? pendingItem.getUnequippedWeight()
                                                    : 0.0;
                                        })
                                .sum();
        if (!hasRoomForWeight(dest, player, item.getUnequippedWeight() + pendingWeight)) {
            sendResult(player, uuid, "rejected", 0);
            return;
        }

        long duration = calculateDuration(item, src, dest, player);
        long endTime = GameTime.getServerTimeMills() + duration;

        pendingTransfers.put(uuid, new PendingTransfer(player, itemId, src, dest, endTime));

        LOGGER.debug(
                "transferItem: accepted uuid={} duration={}ms for {}",
                uuid,
                duration,
                player.getUsername());
        sendAccepted(player, uuid, duration);
    }

    @OnClientCommand
    public static void onCancelTransfer(CancelTransferCommand event) {
        String uuid = event.getUuid();
        PendingTransfer pending = pendingTransfers.get(uuid);
        if (pending != null && pending.player == event.getPlayer()) {
            pendingTransfers.remove(uuid);
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

            // Re-validate before executing
            InventoryItem item = p.src.getItemWithID(p.itemId);
            if (item == null) {
                LOGGER.debug(
                        "processPending: item {} gone from source, rejecting uuid={}",
                        p.itemId,
                        uuid);
                sendResult(p.player, uuid, "rejected", 0);
                continue;
            }

            if (!p.dest.isItemAllowed(item)
                    || !hasRoomForWeight(p.dest, p.player, item.getUnequippedWeight())) {
                LOGGER.debug("processPending: dest can't accept item, rejecting uuid={}", uuid);
                sendResult(p.player, uuid, "rejected", 0);
                continue;
            }

            // Unequip item if worn/held by the player (matches vanilla Transaction.update())
            if (p.src == p.player.getInventory() || p.src.isInCharacterInventory(p.player)) {
                p.player.removeAttachedItem(item);
                if (p.player.isEquipped(item)) {
                    p.player.removeFromHands(item);
                    p.player.removeWornItem(item, false);
                    LuaEventManager.triggerEvent("OnClothingUpdated", p.player);
                    p.player.updateHandEquips();
                    GameServer.sendSyncClothing(p.player, null, item);
                }
            }

            // Mark source container as explored/looted (matches vanilla Transaction.update())
            p.src.setExplored(true);
            p.src.setHasBeenLooted(true);

            // Item state updates (matches vanilla Transaction.update() lines 156-172)
            boolean isReplacedOnCooked = false;
            if (item instanceof Food food) {
                food.setChef(p.player.getUsername());
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

            // Execute the transfer
            p.src.Remove(item);
            p.dest.addItem(item);

            // Broadcast to relevant clients (same as Transaction.update())
            GameServer.sendRemoveItemFromContainer(p.src, item);
            GameServer.sendAddItemToContainer(p.dest, item);

            LOGGER.debug(
                    "processPending: moved item {} for {} uuid={}",
                    item.getFullType(),
                    p.player.getUsername(),
                    uuid);
            sendResult(p.player, uuid, "done", 1);
        }
    }

    /**
     * Cancels all pending transfers for a player. Called when a player disconnects to prevent
     * orphaned transfers.
     */
    public static void cancelAllForPlayer(IsoPlayer player) {
        pendingTransfers
                .entrySet()
                .removeIf(
                        entry -> {
                            if (entry.getValue().player == player) {
                                LOGGER.debug(
                                        "cancelAllForPlayer: removing uuid={} for {}",
                                        entry.getKey(),
                                        player.getUsername());
                                return true;
                            }
                            return false;
                        });
    }

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

            IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
            if (sq == null && GameServer.server) {
                sq = ServerMap.instance.getGridSquare(x, y, z);
            }
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

            IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
            if (sq == null && GameServer.server) {
                sq = ServerMap.instance.getGridSquare(x, y, z);
            }
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

    private static boolean hasRoomForWeight(
            ItemContainer dest, IsoPlayer player, float totalWeight) {
        float capacity = dest.getEffectiveCapacity(player);
        float currentWeight = dest.getCapacityWeight();
        return ItemContainer.floatingPointCorrection(currentWeight) + totalWeight <= capacity;
    }

    /**
     * Calculates transfer duration in milliseconds, replicating {@code Transaction.getDuration()}.
     *
     * <p>The vanilla method multiplies by 20 at the end (converting from Lua-scale time to server
     * millis). The client divides by 20 when it receives the duration to get Lua-scale time for the
     * progress bar. We store the raw millis value (i.e. the vanilla result as-is) and send the
     * Lua-scale value (divided by 20) to the client.
     */
    static long calculateDuration(
            InventoryItem item, ItemContainer src, ItemContainer dest, IsoPlayer player) {
        float maxTime = 120.0f;
        float destCapacityDelta = 1.0f;

        boolean srcInInventory = src == player.getInventory() || src.isInCharacterInventory(player);
        boolean destInInventory =
                dest == player.getInventory() || dest.isInCharacterInventory(player);

        if (srcInInventory && destInInventory) {
            // Inventory-to-inventory (existing logic)
            if (src == player.getInventory() && dest.isInCharacterInventory(player)) {
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

        if (player.hasTrait(CharacterTrait.DEXTROUS)) {
            maxTime *= 0.5f;
        }
        if (player.hasTrait(CharacterTrait.ALL_THUMBS) || player.isWearingAwkwardGloves()) {
            maxTime *= 2.0f;
        }

        // Vanilla multiplies by 20 to convert Lua-scale time to server millis
        return (long) (maxTime * 20.0f);
    }

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
