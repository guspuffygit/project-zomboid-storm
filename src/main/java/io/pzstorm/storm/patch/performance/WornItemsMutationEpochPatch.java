package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Bumps the {@code StormInventoryWeight} epoch on {@code zombie.characters.WornItems.WornItems}
 * mutations &mdash; wearing or removing clothing flips the per-item equipped 0.7&times; weight
 * multiplier in {@code IsoGameCharacter.getInventoryWeight()}, so the memo must not survive an
 * equip change within a tick. Server-only by registration gate ({@code StormEnv.isStormServer()}).
 */
public class WornItemsMutationEpochPatch extends StormClassTransformer {

    public WornItemsMutationEpochPatch() {
        super("zombie.characters.WornItems.WornItems");
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
                                        "setItem", "remove", "clear", "setFromItemVisuals")));
    }
}
