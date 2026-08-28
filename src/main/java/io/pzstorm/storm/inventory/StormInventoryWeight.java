package io.pzstorm.storm.inventory;

import io.pzstorm.storm.metrics.InventoryWeightMemoMetrics;

/**
 * Epoch source for the per-character {@code getInventoryWeight()} memo installed by {@code
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
 * <p>The memo packs {@code (epoch << 32) | floatToRawIntBits(weight)} into one volatile long on the
 * character. A cached value is valid only while the global {@link #epoch} is unchanged. The epoch
 * advances once per server tick ({@code ServerTickAdvice}) and on every inventory-shaped mutation
 * &mdash; {@code ItemContainer} content add/remove ({@code ItemContainerMutationEpochPatch}),
 * worn-item changes ({@code WornItemsMutationEpochPatch}) and hand equip/unequip &mdash; so
 * staleness is bounded by one tick <em>and</em> the {@code TransactionManager} transfer-capacity
 * gate ({@code hasRoomFor} &rarr; {@code getCapacityWeight}) never validates a burst of same-tick
 * transfers against a pre-mutation weight.
 *
 * <p>Deliberately <em>not</em> epoch sources (bounded to at most one tick of drift, and none gates
 * a server decision): fluid amounts, ammo counts, drainable deltas, hotbar attach state and
 * fake-equip flips.
 */
public final class StormInventoryWeight {

    /**
     * Global validity epoch; starts at 1 so the zero-initialized per-character field is a miss.
     * Bumped on the main thread each tick and by mutation advice (rarely off-main from chunk-load
     * workers populating containers &mdash; a lost concurrent increment still changes the value,
     * which is all invalidation needs).
     */
    public static volatile int epoch = 1;

    /** Memo hits (vanilla walk skipped). Main-thread dominant writer; read at scrape time. */
    public static long hits;

    /** Memo misses (vanilla walk ran and the result was stored). */
    public static long misses;

    static {
        InventoryWeightMemoMetrics.init();
    }

    private StormInventoryWeight() {}

    /** Called once per server tick by {@code ServerTickAdvice}. */
    public static void onServerTick() {
        epoch++;
    }

    /** Called by mutation advice on inventory content or equip-state changes. */
    public static void bump() {
        epoch++;
    }
}
