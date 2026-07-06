package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Strides the per-tick orphaned-item sweep in {@code InventoryItemSystem.update()} to every Nth
 * tick. See {@link InventoryItemSweepTickInterval} for the behavior analysis; configured via the
 * {@code Storm.InventoryItemSweepTickInterval} sandbox option.
 */
public class InventoryItemSweepStridePatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.inventoryitemsweepstride.";

    public InventoryItemSweepStridePatch() {
        super("zombie.entity.InventoryItemSystem");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "InventoryItemSweepStrideAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("update")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
