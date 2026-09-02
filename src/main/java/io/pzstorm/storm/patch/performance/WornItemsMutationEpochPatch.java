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
 * Adds the {@code stormOwner} back-reference (public volatile {@code Object}) plus the {@link
 * io.pzstorm.storm.entity.StormWornItemsOwnerHolder} accessors to {@code
 * zombie.characters.WornItems.WornItems}, and bumps the owning character's inventory-weight epoch
 * on every method that mutates the worn list &mdash; wearing or removing clothing flips the
 * per-item equipped weight multiplier in {@code IsoGameCharacter.getInventoryWeight()}. The list is
 * private, so this allowlist is complete: {@code setItem}, {@code remove}, {@code clear}, {@code
 * setFromItemVisuals}, {@code copyFrom} and {@code load}. Server-only by registration gate ({@code
 * StormEnv.isStormServer()}).
 */
public class WornItemsMutationEpochPatch extends StormClassTransformer {

    public WornItemsMutationEpochPatch() {
        super("zombie.characters.WornItems.WornItems");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField(
                        "stormOwner", Object.class, Visibility.PUBLIC, FieldManifestation.VOLATILE)
                .implement(
                        typePool.describe("io.pzstorm.storm.entity.StormWornItemsOwnerHolder")
                                .resolve())
                .intercept(FieldAccessor.ofField("stormOwner"))
                .visit(
                        Advice.to(
                                        typePool.describe(
                                                        "io.pzstorm.storm.advice.inventoryweight"
                                                                + ".WornItemsInvEpochBumpAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.namedOneOf(
                                                "setItem",
                                                "remove",
                                                "clear",
                                                "setFromItemVisuals",
                                                "copyFrom",
                                                "load")));
    }
}
