package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Installs a {@link io.pzstorm.storm.inventory.StormTrackedItemList} as {@code
 * zombie.inventory.ItemContainer.items} at the end of every constructor and of {@code setItems} /
 * {@code emptyIt} (the only assignments of the field), so every content mutation &mdash; through
 * the container's own methods or through the direct {@code getItems().add(...)} sites scattered
 * across the game &mdash; invalidates the owning character's inventory-weight memo. Replaces the
 * earlier method-allowlist approach, which could not see the direct sites and therefore needed a
 * per-tick global epoch bump as a safety net.
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}). Fails loud at weave time
 * if the {@code items} field disappears or changes type.
 */
public class ItemContainerTrackedListPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.inventory.ItemContainer";

    public ItemContainerTrackedListPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        if (typePool.describe(TARGET)
                .resolve()
                .getDeclaredFields()
                .filter(
                        ElementMatchers.named("items")
                                .and(ElementMatchers.fieldType(java.util.ArrayList.class)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "ItemContainerTrackedListPatch: ItemContainer no longer declares"
                            + " ArrayList items — re-verify against the current game source.");
        }
        return builder.visit(
                Advice.to(
                                typePool.describe(
                                                "io.pzstorm.storm.advice.inventoryweight"
                                                        + ".ItemContainerTrackedListAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.isConstructor()
                                        .or(ElementMatchers.namedOneOf("setItems", "emptyIt"))));
    }
}
