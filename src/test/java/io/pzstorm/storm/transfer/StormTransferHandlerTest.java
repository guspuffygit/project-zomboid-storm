package io.pzstorm.storm.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import zombie.characters.IsoPlayer;
import zombie.characters.WornItems.WornItems;
import zombie.characters.traits.CharacterTraits;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoGridSquare;
import zombie.scripting.objects.CharacterTrait;

/**
 * Unit tests for {@link StormTransferHandler} focused on the floor-transfer changes added on this
 * branch: the new {@code Leg} sealed type hierarchy, the new {@code itemWeight} field on {@code
 * PendingTransfer}, and the floor-aware branches in {@link StormTransferHandler#calculateDuration}.
 *
 * <p>Heavyweight game classes ({@code IsoPlayer}, {@code ItemContainer}, {@code InventoryItem},
 * {@code IsoGridSquare}) are fabricated via {@link Unsafe#allocateInstance} plus reflective field
 * pokes — the methods under test only read a handful of fields each.
 */
class StormTransferHandlerTest implements UnitTest {

    private static final Unsafe UNSAFE = unsafe();

    @BeforeEach
    void clearStaticMaps() {
        clearStaticMap("pendingTransfers");
        clearStaticMap("lightweightPickups");
    }

    @AfterEach
    void clearStaticMapsAfter() {
        clearStaticMap("pendingTransfers");
        clearStaticMap("lightweightPickups");
    }

    // ------------------------------------------------------------------
    // Sealed Leg hierarchy
    // ------------------------------------------------------------------

    @Nested
    class LegSealedType {

        @Test
        void containerLegHoldsContainerReference() throws Exception {
            ItemContainer container = newContainer("player");

            StormTransferHandler.ContainerLeg leg =
                    new StormTransferHandler.ContainerLeg(container);

            assertSame(container, leg.container());
        }

        @Test
        void floorPickupLegHoldsCoordinatesAndItemId() {
            StormTransferHandler.FloorPickupLeg leg =
                    new StormTransferHandler.FloorPickupLeg(123, 456, 1, 7777);

            assertEquals(123, leg.x());
            assertEquals(456, leg.y());
            assertEquals(1, leg.z());
            assertEquals(7777, leg.itemId());
        }

        @Test
        void floorDropLegHoldsSquareReference() throws Exception {
            IsoGridSquare square = newSquare(10, 20, 0);

            StormTransferHandler.FloorDropLeg leg = new StormTransferHandler.FloorDropLeg(square);

            assertSame(square, leg.square());
        }

        @Test
        void patternMatchingDispatchesByLegSubtype() throws Exception {
            ItemContainer container = newContainer("player");
            IsoGridSquare square = newSquare(0, 0, 0);

            StormTransferHandler.Leg containerLeg =
                    new StormTransferHandler.ContainerLeg(container);
            StormTransferHandler.Leg pickupLeg =
                    new StormTransferHandler.FloorPickupLeg(1, 2, 3, 4);
            StormTransferHandler.Leg dropLeg = new StormTransferHandler.FloorDropLeg(square);

            assertEquals("container", classify(containerLeg));
            assertEquals("pickup", classify(pickupLeg));
            assertEquals("drop", classify(dropLeg));
        }

        @Test
        void floorPickupLegEqualsByFields() {
            StormTransferHandler.FloorPickupLeg a =
                    new StormTransferHandler.FloorPickupLeg(1, 2, 3, 4);
            StormTransferHandler.FloorPickupLeg b =
                    new StormTransferHandler.FloorPickupLeg(1, 2, 3, 4);
            StormTransferHandler.FloorPickupLeg c =
                    new StormTransferHandler.FloorPickupLeg(1, 2, 3, 99);

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertFalse(a.equals(c));
        }

        private String classify(StormTransferHandler.Leg leg) {
            return switch (leg) {
                case StormTransferHandler.ContainerLeg ignored -> "container";
                case StormTransferHandler.FloorPickupLeg ignored -> "pickup";
                case StormTransferHandler.FloorDropLeg ignored -> "drop";
            };
        }
    }

    // ------------------------------------------------------------------
    // pendingSize / cancelAllForPlayer
    // ------------------------------------------------------------------

    @Nested
    class PendingMap {

        @Test
        void pendingSizeIsZeroOnCleanState() {
            assertEquals(0, StormTransferHandler.pendingSize());
        }

        @Test
        void cancelAllForPlayerNoOpsWhenNothingPending() throws Exception {
            IsoPlayer player = newPlayer(newContainer("player"));

            StormTransferHandler.cancelAllForPlayer(player);

            assertEquals(0, StormTransferHandler.pendingSize());
        }

        @Test
        void cancelAllForPlayerRemovesOnlyMatchingPlayerEntries() throws Exception {
            IsoPlayer alice = newPlayer(newContainer("player"));
            IsoPlayer bob = newPlayer(newContainer("player"));

            ConcurrentHashMap<String, Object> pending = getStaticMap("pendingTransfers");
            pending.put("uuid-a1", newPendingTransferForPlayer(alice));
            pending.put("uuid-a2", newPendingTransferForPlayer(alice));
            pending.put("uuid-b1", newPendingTransferForPlayer(bob));
            assertEquals(3, StormTransferHandler.pendingSize());

            StormTransferHandler.cancelAllForPlayer(alice);

            assertEquals(1, StormTransferHandler.pendingSize());
            assertTrue(pending.containsKey("uuid-b1"));
        }

        @Test
        void cancelAllForPlayerAlsoClearsLightweightState() throws Exception {
            ItemContainer inventory = newContainer("player");
            IsoPlayer player = newPlayer(inventory);

            ConcurrentHashMap<IsoPlayer, Object> lw = getStaticMap("lightweightPickups");
            lw.put(player, newLightweightPickupData("Base.Nail", 5, System.currentTimeMillis()));
            assertTrue(lw.containsKey(player));

            StormTransferHandler.cancelAllForPlayer(player);

            assertFalse(lw.containsKey(player));
        }
    }

    // ------------------------------------------------------------------
    // calculateDuration — container ↔ container (sanity: unchanged baseline)
    // ------------------------------------------------------------------

    @Nested
    class CalculateDurationContainer {

        @Test
        void externalToExternalUses120fBase() throws Exception {
            ItemContainer playerInv = newContainer("player");
            IsoPlayer player = newPlayer(playerInv);
            ItemContainer src = newContainer("crate");
            ItemContainer dest = newContainer("fridge");
            InventoryItem item = newItem("Base.Foo", 1.0f);

            long millis =
                    StormTransferHandler.calculateDuration(
                            item,
                            new StormTransferHandler.ContainerLeg(src),
                            new StormTransferHandler.ContainerLeg(dest),
                            player);

            // 120 * 1.0 (w=1) * 1.0 (cap) = 120f Lua-scale -> 2400 millis
            assertEquals(2400L, millis);
        }

        @Test
        void playerInventoryToExternalUses50fBase() throws Exception {
            ItemContainer playerInv = newContainer("player");
            IsoPlayer player = newPlayer(playerInv);
            ItemContainer dest = newContainer("crate");
            InventoryItem item = newItem("Base.Foo", 1.0f);

            long millis =
                    StormTransferHandler.calculateDuration(
                            item,
                            new StormTransferHandler.ContainerLeg(playerInv),
                            new StormTransferHandler.ContainerLeg(dest),
                            player);

            // 50 * 1.0 * 1.0 = 50 Lua -> 1000 millis
            assertEquals(1000L, millis);
        }
    }

    // ------------------------------------------------------------------
    // calculateDuration — floor drops (new behaviour on this branch)
    // ------------------------------------------------------------------

    @Nested
    class CalculateDurationFloorDrop {

        @Test
        void playerInventoryToFloorMultipliesBy0_1() throws Exception {
            ItemContainer playerInv = newContainer("player");
            IsoPlayer player = newPlayer(playerInv);
            IsoGridSquare square = newSquare(0, 0, 0);
            InventoryItem item = newItem("Base.Foo", 1.0f);

            long millis =
                    StormTransferHandler.calculateDuration(
                            item,
                            new StormTransferHandler.ContainerLeg(playerInv),
                            new StormTransferHandler.FloorDropLeg(square),
                            player);

            // 50 (player inv -> floor base) * 1.0 (weight) * 1.0 (capacity) * 0.1 (floor mult)
            // = 5.0f Lua-scale -> *20 = 100 millis
            assertEquals(100L, millis);
        }

        @Test
        void externalContainerToFloorMultipliesBy0_2() throws Exception {
            ItemContainer playerInv = newContainer("player");
            IsoPlayer player = newPlayer(playerInv);
            ItemContainer crate = newContainer("crate");
            IsoGridSquare square = newSquare(0, 0, 0);
            InventoryItem item = newItem("Base.Foo", 1.0f);

            long millis =
                    StormTransferHandler.calculateDuration(
                            item,
                            new StormTransferHandler.ContainerLeg(crate),
                            new StormTransferHandler.FloorDropLeg(square),
                            player);

            // External src to floor keeps the 120f base, then 0.2f multiplier
            // 120 * 1.0 * 1.0 * 0.2 = 24f Lua-scale -> *20 = 480 millis
            assertEquals(480L, millis);
        }

        @Test
        void floorDropDoesNotApplyDestCapacityDelta() throws Exception {
            // Floor drops skip the 0.4f minimum on destCapacityDelta — there's no real
            // destination container whose fill ratio we could scale on.
            ItemContainer playerInv = newContainer("player");
            IsoPlayer player = newPlayer(playerInv);
            IsoGridSquare square = newSquare(0, 0, 0);
            InventoryItem item = newItem("Base.Foo", 2.0f); // heavier item

            long millis =
                    StormTransferHandler.calculateDuration(
                            item,
                            new StormTransferHandler.ContainerLeg(playerInv),
                            new StormTransferHandler.FloorDropLeg(square),
                            player);

            // 50 * 2.0 * 1.0 * 0.1 = 10f Lua-scale -> *20 = 200 millis
            assertEquals(200L, millis);
        }

        @Test
        void weightIsClampedAtThreeForFloorDrop() throws Exception {
            ItemContainer playerInv = newContainer("player");
            IsoPlayer player = newPlayer(playerInv);
            IsoGridSquare square = newSquare(0, 0, 0);
            InventoryItem item = newItem("Base.Heavy", 99.0f);

            long millis =
                    StormTransferHandler.calculateDuration(
                            item,
                            new StormTransferHandler.ContainerLeg(playerInv),
                            new StormTransferHandler.FloorDropLeg(square),
                            player);

            // Weight clamped to 3.0: 50 * 3.0 * 1.0 * 0.1 = 15f Lua-scale -> *20 = 300 millis
            assertEquals(300L, millis);
        }
    }

    // ------------------------------------------------------------------
    // calculateDuration — floor pickups (new path on this branch)
    // ------------------------------------------------------------------

    @Nested
    class CalculateDurationFloorPickup {

        @Test
        void floorPickupToPlayerInventoryUses50fBase() throws Exception {
            ItemContainer playerInv = newContainer("player");
            IsoPlayer player = newPlayer(playerInv);
            InventoryItem item = newItem("Base.Foo", 1.0f);

            long millis =
                    StormTransferHandler.calculateDuration(
                            item,
                            new StormTransferHandler.FloorPickupLeg(0, 0, 0, item.getID()),
                            new StormTransferHandler.ContainerLeg(playerInv),
                            player);

            // Floor pickups don't apply weight scaling (vanilla: itemId == -1 in Transaction)
            // 50f Lua-scale -> 1000 millis
            assertEquals(1000L, millis);
        }

        @Test
        void floorPickupToExternalContainerUses120fBase() throws Exception {
            ItemContainer playerInv = newContainer("player");
            IsoPlayer player = newPlayer(playerInv);
            ItemContainer crate = newContainer("crate");
            InventoryItem item = newItem("Base.Foo", 1.0f);

            long millis =
                    StormTransferHandler.calculateDuration(
                            item,
                            new StormTransferHandler.FloorPickupLeg(0, 0, 0, item.getID()),
                            new StormTransferHandler.ContainerLeg(crate),
                            player);

            assertEquals(2400L, millis);
        }

        @Test
        void firstLightweightPickupIsFullDuration() throws Exception {
            // Vanilla: the lightweight fast-path zeroes the duration ONLY on the second+
            // identical pickup within 2s. The very first pickup pays the full base time.
            ItemContainer playerInv = newContainer("player");
            IsoPlayer player = newPlayer(playerInv);
            InventoryItem item = newItem("Base.Nail", 0.05f);

            long millis =
                    StormTransferHandler.calculateDuration(
                            item,
                            new StormTransferHandler.FloorPickupLeg(0, 0, 0, item.getID()),
                            new StormTransferHandler.ContainerLeg(playerInv),
                            player);

            // First call: lastPickupMillis == 0, branch sets count=0 and exits without zeroing
            assertEquals(1000L, millis);
        }

        @Test
        void secondLightweightPickupOfSameTypeIsInstant() throws Exception {
            // Seed lightweight burst state so the second identical pickup hits the 0ms branch.
            ItemContainer playerInv = newContainer("player");
            IsoPlayer player = newPlayer(playerInv);
            InventoryItem item = newItem("Base.Nail", 0.05f);

            ConcurrentHashMap<IsoPlayer, Object> lw = getStaticMap("lightweightPickups");
            lw.put(player, newLightweightPickupData("Base.Nail", 1, System.currentTimeMillis()));

            long millis =
                    StormTransferHandler.calculateDuration(
                            item,
                            new StormTransferHandler.FloorPickupLeg(0, 0, 0, item.getID()),
                            new StormTransferHandler.ContainerLeg(playerInv),
                            player);

            assertEquals(0L, millis);
        }

        @Test
        void lightweightBurstStopsAfter19Items() throws Exception {
            ItemContainer playerInv = newContainer("player");
            IsoPlayer player = newPlayer(playerInv);
            InventoryItem item = newItem("Base.Nail", 0.05f);

            // Burst at the 19-item cap: next pickup must NOT zero out, must reset count
            ConcurrentHashMap<IsoPlayer, Object> lw = getStaticMap("lightweightPickups");
            lw.put(player, newLightweightPickupData("Base.Nail", 19, System.currentTimeMillis()));

            long millis =
                    StormTransferHandler.calculateDuration(
                            item,
                            new StormTransferHandler.FloorPickupLeg(0, 0, 0, item.getID()),
                            new StormTransferHandler.ContainerLeg(playerInv),
                            player);

            assertEquals(1000L, millis);
            assertEquals(0, getLightweightCount(player));
        }

        @Test
        void heavyItemPickupSkipsBurstPath() throws Exception {
            ItemContainer playerInv = newContainer("player");
            IsoPlayer player = newPlayer(playerInv);
            InventoryItem item = newItem("Base.Brick", 2.0f);

            // Pre-seed burst state — it must be ignored for heavy items (weight > 0.1).
            ConcurrentHashMap<IsoPlayer, Object> lw = getStaticMap("lightweightPickups");
            lw.put(player, newLightweightPickupData("Base.Brick", 5, System.currentTimeMillis()));

            long millis =
                    StormTransferHandler.calculateDuration(
                            item,
                            new StormTransferHandler.FloorPickupLeg(0, 0, 0, item.getID()),
                            new StormTransferHandler.ContainerLeg(playerInv),
                            player);

            assertEquals(1000L, millis);
        }

        @Test
        void lightweightBurstResetsOnDifferentItemType() throws Exception {
            ItemContainer playerInv = newContainer("player");
            IsoPlayer player = newPlayer(playerInv);
            InventoryItem item = newItem("Base.Screw", 0.05f);

            ConcurrentHashMap<IsoPlayer, Object> lw = getStaticMap("lightweightPickups");
            lw.put(player, newLightweightPickupData("Base.Nail", 3, System.currentTimeMillis()));

            long millis =
                    StormTransferHandler.calculateDuration(
                            item,
                            new StormTransferHandler.FloorPickupLeg(0, 0, 0, item.getID()),
                            new StormTransferHandler.ContainerLeg(playerInv),
                            player);

            // Different type -> burst resets, full duration applied
            assertEquals(1000L, millis);
            assertEquals(0, getLightweightCount(player));
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Container with the given type and {@code id == 0}; parent and containingItem left null. */
    private static ItemContainer newContainer(String type) throws Exception {
        ItemContainer container = (ItemContainer) UNSAFE.allocateInstance(ItemContainer.class);
        setField(ItemContainer.class, container, "type", type);
        return container;
    }

    /** Player with the supplied inventory, empty traits, and an empty worn-items collection. */
    private static IsoPlayer newPlayer(ItemContainer inventory) throws Exception {
        IsoPlayer player = (IsoPlayer) UNSAFE.allocateInstance(IsoPlayer.class);

        Class<?> gameChar = Class.forName("zombie.characters.IsoGameCharacter");
        setField(gameChar, player, "inventory", inventory);

        // CharacterTraits.get() does a raw Map.get unboxed to boolean — null keys NPE. Pre-seed
        // every trait calculateDuration consults to false.
        CharacterTraits traits = (CharacterTraits) UNSAFE.allocateInstance(CharacterTraits.class);
        LinkedHashMap<CharacterTrait, Boolean> traitMap = new LinkedHashMap<>();
        traitMap.put(CharacterTrait.DEXTROUS, false);
        traitMap.put(CharacterTrait.ALL_THUMBS, false);
        setField(CharacterTraits.class, traits, "traits", traitMap);
        setField(gameChar, player, "characterTraits", traits);

        WornItems wornItems = (WornItems) UNSAFE.allocateInstance(WornItems.class);
        setField(WornItems.class, wornItems, "items", new java.util.ArrayList<>());
        setField(gameChar, player, "wornItems", wornItems);

        return player;
    }

    /**
     * InventoryItem populated only with the fields read on the floor-transfer code paths. {@code
     * fullType} must equal {@code module + "." + type} — getFullType() asserts that with {@code
     * -ea} on, which is the JVM mode tests run in.
     */
    private static InventoryItem newItem(String fullType, float weight) throws Exception {
        int dot = fullType.indexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("fullType must be 'module.type', got: " + fullType);
        }
        String module = fullType.substring(0, dot);
        String type = fullType.substring(dot + 1);

        InventoryItem item = (InventoryItem) UNSAFE.allocateInstance(InventoryItem.class);
        // name != fullType makes getActualWeight() return `actualWeight` rather than 0.
        setField(InventoryItem.class, item, "name", "Display:" + fullType);
        setField(InventoryItem.class, item, "fullType", fullType);
        setField(InventoryItem.class, item, "module", module);
        setField(InventoryItem.class, item, "type", type);
        setField(InventoryItem.class, item, "weight", weight);
        setField(InventoryItem.class, item, "actualWeight", weight);
        // id field used by getID(); a unique id avoids collisions between fixtures.
        setField(InventoryItem.class, item, "id", nextItemId());
        return item;
    }

    private static IsoGridSquare newSquare(int x, int y, int z) throws Exception {
        IsoGridSquare square = (IsoGridSquare) UNSAFE.allocateInstance(IsoGridSquare.class);
        setField(IsoGridSquare.class, square, "x", x);
        setField(IsoGridSquare.class, square, "y", y);
        setField(IsoGridSquare.class, square, "z", z);
        return square;
    }

    /**
     * Builds a {@code PendingTransfer} record by reflection. The record is declared {@code private}
     * inside {@link StormTransferHandler}, so we have to flip accessibility on the canonical
     * constructor before invoking it.
     */
    private static Object newPendingTransferForPlayer(IsoPlayer player) throws Exception {
        Class<?> pendingCls =
                Class.forName("io.pzstorm.storm.transfer.StormTransferHandler$PendingTransfer");
        Class<?> legCls = Class.forName("io.pzstorm.storm.transfer.StormTransferHandler$Leg");
        var ctor =
                pendingCls.getDeclaredConstructor(
                        IsoPlayer.class,
                        int.class,
                        legCls,
                        legCls,
                        String.class,
                        String.class,
                        float.class,
                        long.class,
                        long.class,
                        long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(player, 0, null, null, "src", "dest", 0.0f, 0L, 0L, 0L);
    }

    /** Builds a {@code LightweightPickupData} by reflection, since the inner class is private. */
    private static Object newLightweightPickupData(String fullType, int count, long lastMillis)
            throws Exception {
        Class<?> cls =
                Class.forName(
                        "io.pzstorm.storm.transfer.StormTransferHandler$LightweightPickupData");
        Object data = UNSAFE.allocateInstance(cls);
        setField(cls, data, "lastItemFullType", fullType);
        setField(cls, data, "itemsCount", count);
        setField(cls, data, "lastPickupMillis", lastMillis);
        return data;
    }

    private static int getLightweightCount(IsoPlayer player) throws Exception {
        Object data = getStaticMap("lightweightPickups").get(player);
        if (data == null) return -1;
        Field f = data.getClass().getDeclaredField("itemsCount");
        f.setAccessible(true);
        return f.getInt(data);
    }

    private static int nextItemId() {
        return ITEM_ID_SEQ++;
    }

    private static int ITEM_ID_SEQ = 1;

    // ------------------------------------------------------------------
    // Reflection plumbing
    // ------------------------------------------------------------------

    private static void setField(Class<?> owner, Object target, String name, Object value)
            throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void setField(Class<?> owner, Object target, String name, int value)
            throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(target, value);
    }

    private static void setField(Class<?> owner, Object target, String name, long value)
            throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.setLong(target, value);
    }

    private static void setField(Class<?> owner, Object target, String name, float value)
            throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> ConcurrentHashMap<K, V> getStaticMap(String name) {
        try {
            Field f = StormTransferHandler.class.getDeclaredField(name);
            f.setAccessible(true);
            return (ConcurrentHashMap<K, V>) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("getStaticMap(" + name + ")", e);
        }
    }

    private static void clearStaticMap(String name) {
        getStaticMap(name).clear();
    }

    private static Unsafe unsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Unsafe u = (Unsafe) f.get(null);
            assertNotNull(u);
            return u;
        } catch (Exception e) {
            throw new RuntimeException("Unable to acquire sun.misc.Unsafe", e);
        }
    }
}
