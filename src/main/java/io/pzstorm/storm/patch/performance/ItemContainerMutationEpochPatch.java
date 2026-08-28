package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Bumps the {@code StormInventoryWeight} epoch on every {@code zombie.inventory.ItemContainer}
 * content mutation, so the per-character weight memo can never serve a pre-mutation value to a
 * same-tick capacity check ({@code TransactionManager} validates transfers through {@code
 * hasRoomFor} &rarr; {@code getCapacityWeight}).
 *
 * <p>The method list is an explicit allowlist of every {@code items}-mutating method (adds,
 * removes, {@code clear}/{@code emptyIt}/{@code setItems} load-and-wipe paths). Deliberately
 * excluded: {@code addItemsToProcessItems}/{@code removeItemsFromProcessItems} (chunk-streaming
 * bookkeeping, contents unchanged) and the null-entry cleanup removals inside {@code
 * getFirst}/{@code getSome}/{@code getAll} (weight-neutral). A global epoch is deliberately coarse:
 * any mutation anywhere (including inside nested bags, which are separate {@code ItemContainer}
 * instances) invalidates all memos &mdash; the cost is one extra full weigh per character, the
 * vanilla per-call baseline.
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}).
 */
public class ItemContainerMutationEpochPatch extends StormClassTransformer {

    public ItemContainerMutationEpochPatch() {
        super("zombie.inventory.ItemContainer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(
                                                "io.pzstorm.storm.advice.inventoryweight"
                                                        + ".InventoryWeightEpochBumpAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.namedOneOf(
                                        "addItem",
                                        "addItems",
                                        "AddItem",
                                        "AddItems",
                                        "AddItemBlind",
                                        "DoAddItem",
                                        "DoAddItemBlind",
                                        "Remove",
                                        "RemoveAll",
                                        "RemoveOneOf",
                                        "DoRemoveItem",
                                        "removeItemOnServer",
                                        "removeAllItems",
                                        "removeItemWithID",
                                        "removeItemWithIDRecurse",
                                        "emptyIt",
                                        "clear",
                                        "setItems")));
    }
}
