package io.pzstorm.storm.inventory;

import io.pzstorm.storm.entity.StormInvWeightHolder;
import io.pzstorm.storm.entity.StormWornItemsOwnerHolder;
import io.pzstorm.storm.metrics.InventoryWeightMemoMetrics;
import zombie.entity.Component;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;

/**
 * Epoch sources for the per-character {@code getInventoryWeight()} memo installed by {@code
 * IsoGameCharacterInvWeightMemoPatch}.
 *
 * <p>On a populated server every character runs at least two full recursive inventory weighs per
 * tick &mdash; {@code Moodle.Update} (HEAVY_LOAD level) and {@code Thermoregulator.update} both
 * call {@code ItemContainer.getCapacityWeight()}, which for a character delegates to {@code
 * IsoGameCharacter.getInventoryWeight()} &mdash; plus incidental calls from Lua, path speed and
 * transfer capacity checks. Each weigh walks the whole top-level inventory and recurses into every
 * bag, doing a {@code wornItems.contains} linear scan, a {@code
 * getDisplayName().equals(getFullType())} string compare and a tag set probe per item (10.4% of
 * player-update time, ~1.4 ms/tick of main at 144 players, ATF profile 2026-08-27).
 *
 * <p>The memo packs {@code ((epoch + 1) << 32) | floatToRawIntBits(weight)} into one volatile long
 * on the character, validated against the character's own {@code stormInvEpoch} (see {@link
 * StormInvWeightHolder}). The first version of this memo used a single global epoch that also
 * advanced once per tick as a safety net; that capped the hit rate at ~50% (one hit per tick per
 * character, scan #10, 2026-09-02). This version has no tick bump: instead every input the weigh
 * reads is an epoch source, routed to the owning character:
 *
 * <ul>
 *   <li>container contents &mdash; {@code ItemContainer.items} is replaced by a {@link
 *       StormTrackedItemList} ({@code ItemContainerTrackedListPatch}), so every add/remove/clear,
 *       including the direct {@code getItems().add(...)} sites in {@code IsoZombie}, {@code
 *       ContainerID}, {@code IsoDeadBody}, {@code VehiclePart} and the RDS stories, lands in {@link
 *       #bumpContainer};
 *   <li>worn items ({@code WornItemsMutationEpochPatch}) and hand items / inventory swap / {@code
 *       onWornItemsChanged} ({@code CharacterInvEpochBumpAdvice}) &mdash; the equipped weight
 *       multiplier;
 *   <li>per-item weight inputs ({@code InventoryItemInvEpochPatch}, {@code FoodInvEpochPatch},
 *       {@code HandWeaponInvEpochPatch}): {@code setActualWeight} (drainables route through it),
 *       ammo count, hotbar slot, {@code setName} (the {@code displayName == fullType} zero-weight
 *       rule), extra items, food hunger/thirst/base-hunger, weapon parts;
 *   <li>fluid amounts ({@code FluidContainerInvEpochPatch}) via the container's owning item.
 * </ul>
 *
 * <p>Known, accepted gaps (all pre-existing vanilla staleness or unreachable in practice): {@code
 * Moveable} writes {@code actualWeight} directly, but only during construction before the item is
 * in any container; {@code subList} views of a tracked list bypass the overrides except for {@code
 * clear} (which routes through {@code removeRange}); a world-object fluid container ({@code
 * IsoObject} owner) is not routed because it never sits in a character inventory.
 *
 * <p>All bump paths are null-safe and instanceof-guarded, so an entity that missed a field patch
 * simply never memoizes.
 */
public final class StormInventoryWeight {

    /** Memo hits (vanilla walk skipped). Main-thread dominant writer; read at scrape time. */
    public static long hits;

    /** Memo misses (vanilla walk ran and the result was stored). */
    public static long misses;

    /** Longest {@code containingItem} chain followed by {@link #bumpContainer}; a cycle guard. */
    private static final int MAX_CONTAINER_HOPS = 32;

    static {
        InventoryWeightMemoMetrics.init();
    }

    private StormInventoryWeight() {}

    /** Invalidates one character's memo. No-op for anything that is not a holder. */
    public static void bumpCharacter(Object character) {
        if (character instanceof StormInvWeightHolder) {
            StormInvWeightHolder holder = (StormInvWeightHolder) character;
            holder.setStormInvEpoch(holder.getStormInvEpoch() + 1);
        }
    }

    /**
     * Invalidates the character whose inventory tree contains {@code container}: follows {@code
     * containingItem} (the bag holding this container) outward to the top-level container, whose
     * {@code parent} is the character for a character inventory. World containers end at an {@code
     * IsoObject} parent and fall out of the holder check.
     */
    public static void bumpContainer(Object container) {
        Object current = container;
        for (int hops = 0; hops < MAX_CONTAINER_HOPS && current instanceof ItemContainer; hops++) {
            ItemContainer ic = (ItemContainer) current;
            InventoryItem containingItem = ic.containingItem;
            if (containingItem == null) {
                bumpCharacter(ic.parent);
                return;
            }
            current = containingItem.getContainer();
        }
    }

    /** Invalidates the character whose inventory tree holds {@code item}, if any. */
    public static void bumpItem(Object item) {
        if (item instanceof InventoryItem) {
            bumpContainer(((InventoryItem) item).getContainer());
        }
    }

    /** Invalidates the character stamped as the owner of a {@code WornItems}, if any. */
    public static void bumpWornItems(Object wornItems) {
        if (wornItems instanceof StormWornItemsOwnerHolder) {
            bumpCharacter(((StormWornItemsOwnerHolder) wornItems).getStormOwner());
        }
    }

    /** Invalidates via the fluid container's owning inventory item, if it has one. */
    public static void bumpFluidContainer(Object fluidContainer) {
        if (fluidContainer instanceof Component) {
            bumpItem(((Component) fluidContainer).getOwner());
        }
    }
}
