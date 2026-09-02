package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Implements {@link io.pzstorm.storm.entity.StormVisualFieldHolder} onto {@code
 * zombie.inventory.InventoryItem}, exposing the protected {@code visual} field to {@code
 * StormClothingVisuals}. Server-only by registration gate. Fails loud if the field is renamed.
 */
public class InventoryItemVisualFieldPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.inventory.InventoryItem";

    public InventoryItemVisualFieldPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        if (typePool.describe(TARGET)
                .resolve()
                .getDeclaredFields()
                .filter(ElementMatchers.named("visual"))
                .isEmpty()) {
            throw new IllegalStateException(
                    "InventoryItemVisualFieldPatch: InventoryItem no longer declares 'visual' —"
                            + " re-verify against the current game source.");
        }
        return builder.implement(
                        typePool.describe("io.pzstorm.storm.entity.StormVisualFieldHolder")
                                .resolve())
                .intercept(FieldAccessor.ofField("visual"));
    }
}
