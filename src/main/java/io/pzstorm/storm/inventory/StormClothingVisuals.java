package io.pzstorm.storm.inventory;

import io.pzstorm.storm.entity.StormVisualFieldHolder;
import io.pzstorm.storm.entity.StormWornVisualsHolder;
import io.pzstorm.storm.metrics.ClothingVisualsMemoMetrics;
import zombie.characters.IsoGameCharacter;
import zombie.characters.WornItems.WornItems;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.inventory.InventoryItem;

/**
 * Substitution targets for the three per-tick clothing-visual resolutions in the player update:
 * {@code Thermoregulator.updateClothing} and {@code ClothingWetness.updateWetness} each rebuild the
 * character's {@code ItemVisuals} through {@code IsoGameCharacter.getItemVisuals}, and {@code
 * Thermoregulator$ThermalNode.calculateInsulation} plus {@code updateWetness} call {@code
 * InventoryItem.getVisual()} per worn clothing item per body part. Every {@code getVisual()} runs
 * {@code getClothingItem()} — {@code getAlternateModelName()} (container parent + hand-item
 * checks), three case-insensitive string compares and, for the alternate-model variants, a {@code
 * ScriptManager.getItem} lookup — only to hand back the {@code visual} field it already holds.
 * Together ~6% of player update on ATF prod (scan #10, 2026-09-02).
 *
 * <p>{@link #cachedVisual(Object)} returns the item's existing {@code visual} field when it is
 * non-null and falls back to {@code getVisual()} otherwise. {@link #fillItemVisuals(Object,
 * Object)} keeps a per-character memo ({@link Memo}: the worn item per index and the visual last
 * seen for it) and only calls {@code getVisual()} for an index whose item changed, whose memoised
 * visual is null, or whose {@code visual} field no longer matches. The memo is fully
 * self-validating against the worn-items list on every call — it does not depend on the inventory
 * epoch — so a worn-list mutation that reaches no epoch source still cannot serve a stale item.
 *
 * <p>Equivalence: {@code getVisual()} returns exactly {@code this.visual} whenever the item's
 * {@code ClothingItem} resolves and is ready; the field is non-null only after such a call. Two
 * documented divergences at these sites only (all other callers of {@code getVisual()} / {@code
 * getItemVisuals()} are untouched): (1) the per-call {@code setClothingItemName} / {@code
 * setAlternateModelName} resync is skipped — the consumers here read only {@code getHole(part)} and
 * the visual's identity, neither of which those setters touch, and every other consumer resyncs on
 * its own call; (2) if the clothing asset later became unready (the server never unloads it)
 * vanilla would drop the visual and NPE in {@code calculateInsulation} / break out of the wetness
 * loop, while this keeps serving the last visual.
 *
 * <p>Main-thread only (character update pass); {@link #hits}/{@link #misses} are plain statics read
 * by {@link ClothingVisualsMemoMetrics}.
 */
public final class StormClothingVisuals {

    /** {@link #fillItemVisuals} calls that made no {@code getVisual()} call. */
    public static long hits;

    /** {@link #fillItemVisuals} calls that made at least one {@code getVisual()} call. */
    public static long misses;

    static {
        ClothingVisualsMemoMetrics.init();
    }

    private StormClothingVisuals() {}

    /** Per-character memo stored in {@link StormWornVisualsHolder}. */
    static final class Memo {
        final InventoryItem[] items;
        final ItemVisual[] visuals;

        Memo(int size) {
            this.items = new InventoryItem[size];
            this.visuals = new ItemVisual[size];
        }
    }

    /**
     * Replacement for {@code item.getVisual()} at the hole-check sites; the receiver arrives as the
     * argument, typed {@code Object} so resolving this method at patch registration loads no
     * patched class.
     */
    public static ItemVisual cachedVisual(Object item) {
        if (item instanceof StormVisualFieldHolder) {
            Object visual = ((StormVisualFieldHolder) item).getStormVisualField();
            if (visual != null) {
                return (ItemVisual) visual;
            }
        }
        return ((InventoryItem) item).getVisual();
    }

    /**
     * Replacement for {@code character.getItemVisuals(itemVisuals)}: same output list (cleared,
     * then one visual per worn item that has one, in worn order, each with {@code setInventoryItem}
     * applied), served from the memo where the item and its visual are unchanged.
     */
    public static void fillItemVisuals(Object character, Object itemVisuals) {
        IsoGameCharacter chr = (IsoGameCharacter) character;
        ItemVisuals out = (ItemVisuals) itemVisuals;
        WornItems worn = chr.getWornItems();
        if (!(chr instanceof StormWornVisualsHolder) || worn == null) {
            chr.getItemVisuals(out);
            return;
        }
        StormWornVisualsHolder holder = (StormWornVisualsHolder) chr;
        int size = worn.size();
        Memo memo = (Memo) holder.getStormWornVisuals();
        if (memo == null || memo.items.length != size) {
            memo = new Memo(size);
            holder.setStormWornVisuals(memo);
        }
        boolean resolved = false;
        out.clear();
        for (int i = 0; i < size; i++) {
            InventoryItem item = worn.getItemByIndex(i);
            ItemVisual visual = memo.visuals[i];
            if (item != memo.items[i]) {
                memo.items[i] = item;
                visual = null;
            }
            Object field =
                    item instanceof StormVisualFieldHolder
                            ? ((StormVisualFieldHolder) item).getStormVisualField()
                            : null;
            if (visual == null || visual != field) {
                visual = item.getVisual();
                memo.visuals[i] = visual;
                resolved = true;
            }
            if (visual != null) {
                visual.setInventoryItem(item);
                out.add(visual);
            }
        }
        if (resolved) {
            misses++;
        } else {
            hits++;
        }
    }
}
