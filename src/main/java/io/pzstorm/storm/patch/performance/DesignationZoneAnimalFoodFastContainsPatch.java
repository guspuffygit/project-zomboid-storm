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
 * Swaps {@code DesignationZoneAnimal.foodOnGround} for {@link
 * io.pzstorm.storm.util.StormFastContainsList} at construction time: {@code addFoodOnGround} runs a
 * linear membership probe per candidate item (ATF profile 2026-08-26, 135 players: part of the
 * ~2.4% of main spent in {@code ArrayList} linear scans on animal-zone-heavy servers). Element type
 * {@code IsoWorldInventoryObject} overrides neither {@code equals} nor {@code hashCode}, so mirror
 * semantics match the vanilla scan.
 *
 * <p>A {@link ModifierAdjustment} strips {@code final} from the field so the constructor-exit write
 * verifies; the copy in the advice preserves anything the constructor body's {@code check()} call
 * already added.
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}).
 */
public class DesignationZoneAnimalFoodFastContainsPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.areas.DesignationZoneAnimal";
    private static final String PKG = "io.pzstorm.storm.advice.fastcontains.";

    public DesignationZoneAnimalFoodFastContainsPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredFields().filter(ElementMatchers.named("foodOnGround")).isEmpty()) {
            throw new IllegalStateException(
                    "DesignationZoneAnimalFoodFastContainsPatch: DesignationZoneAnimal no longer"
                            + " declares foodOnGround — the constructor swap would silently leave"
                            + " the vanilla list in place. Re-verify against the current game"
                            + " source.");
        }
        return builder.visit(
                        new ModifierAdjustment()
                                .withFieldModifiers(
                                        ElementMatchers.named("foodOnGround"),
                                        FieldManifestation.PLAIN))
                .visit(
                        Advice.to(
                                        typePool.describe(
                                                        PKG + "DesignationZoneAnimalFoodSwapAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.isConstructor()));
    }
}
