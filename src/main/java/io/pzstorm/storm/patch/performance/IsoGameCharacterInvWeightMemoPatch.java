package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.FieldManifestation;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Adds the {@code stormInvWeight} field (public volatile {@code long}) to {@code
 * zombie.characters.IsoGameCharacter}, implements {@link
 * io.pzstorm.storm.entity.StormInvWeightHolder} with accessors over it, memoizes {@code
 * getInventoryWeight()} via {@code InventoryWeightMemoAdvice}, and bumps the validity epoch on hand
 * equip changes ({@code setPrimaryHandItem}/{@code setSecondaryHandItem} change the equipped
 * 0.7&times; weight multiplier).
 *
 * <p>Covers every subclass &mdash; {@code IsoPlayer}, {@code IsoZombie}, and {@code IsoAnimal}.
 * Server-only by registration gate ({@code StormEnv.isStormServer()}). See {@link
 * io.pzstorm.storm.inventory.StormInventoryWeight} for the rationale and staleness contract.
 */
public class IsoGameCharacterInvWeightMemoPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.inventoryweight.";

    public IsoGameCharacterInvWeightMemoPatch() {
        super("zombie.characters.IsoGameCharacter");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField(
                        "stormInvWeight",
                        long.class,
                        Visibility.PUBLIC,
                        FieldManifestation.VOLATILE)
                .implement(
                        typePool.describe("io.pzstorm.storm.entity.StormInvWeightHolder").resolve())
                .intercept(FieldAccessor.ofField("stormInvWeight"))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "InventoryWeightMemoAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("getInventoryWeight")
                                                .and(ElementMatchers.takesArguments(0))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "InventoryWeightEpochBumpAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.namedOneOf(
                                                "setPrimaryHandItem", "setSecondaryHandItem")));
    }
}
