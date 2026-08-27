package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.ModifierAdjustment;
import net.bytebuddy.description.modifier.FieldManifestation;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Swaps {@code IsoCell.processItems} and {@code IsoCell.processWorldItems} for {@link
 * io.pzstorm.storm.util.StormFastContainsList} at construction time (ATF profile 2026-08-26, 135
 * players: {@code ProcessRemoveItems}'s per-tick {@code removeAll} against usually-empty remove
 * lists plus {@code addToProcessItems}'s linear membership probes were ~0.9% of main — ~0.5
 * ms/tick).
 *
 * <p>Two visitor steps: a {@link ModifierAdjustment} strips {@code final} from both fields (the
 * constructor-exit write would not verify otherwise), then constructor-exit advice replaces the
 * vanilla lists, copying any contents the constructor body added. Element type {@code
 * InventoryItem} overrides neither {@code equals} nor {@code hashCode}, so the mirror's semantics
 * match the vanilla linear scan exactly.
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}); {@code addToProcessItems}
 * is client-gated vanilla code and stays untouched on client JVMs.
 */
public class IsoCellProcessListsFastContainsPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.IsoCell";
    private static final String PKG = "io.pzstorm.storm.advice.fastcontains.";

    public IsoCellProcessListsFastContainsPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredFields().filter(ElementMatchers.named("processItems")).isEmpty()
                || target.getDeclaredFields()
                        .filter(ElementMatchers.named("processWorldItems"))
                        .isEmpty()) {
            throw new IllegalStateException(
                    "IsoCellProcessListsFastContainsPatch: IsoCell no longer declares processItems"
                            + " and processWorldItems — the constructor swap would silently leave"
                            + " vanilla lists in place. Re-verify against the current game"
                            + " source.");
        }
        return builder.visit(
                        new ModifierAdjustment()
                                .withFieldModifiers(
                                        ElementMatchers.named("processItems")
                                                .or(ElementMatchers.named("processWorldItems")),
                                        FieldManifestation.PLAIN))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "IsoCellProcessListsSwapAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.isConstructor()));
    }
}
