package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the whole-cell vehicle scan in {@code IsoGameCharacter.checkIsNearVehicle} with the
 * guarded fast path in {@code StormCheckIsNearVehicle}: skip outright for non-sneaking characters
 * (the method's only side effect is sneaking-gated and both call sites discard the return), and a
 * chunk-windowed scan for sneakers. Vanilla runs the scan per player per tick over every loaded
 * vehicle; profiling on a loaded server (128 players, ~1,900 vehicles) attributed ~1.4% of
 * main-thread CPU to it. Server-only by registration gate.
 *
 * <p>Kill switch: the {@code Storm.CheckIsNearVehicleFastPath} sandbox option ({@code false}
 * restores vanilla behavior; live-appliable via admin sandbox push).
 */
public class IsoGameCharacterCheckIsNearVehiclePatch extends StormClassTransformer {

    private static final String TARGET = "zombie.characters.IsoGameCharacter";
    private static final String PKG = "io.pzstorm.storm.advice.checkisnearvehicle.";

    public IsoGameCharacterCheckIsNearVehiclePatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(ElementMatchers.named("checkIsNearVehicle"))
                .isEmpty()) {
            throw new IllegalStateException(
                    "IsoGameCharacterCheckIsNearVehiclePatch: IsoGameCharacter no longer declares"
                            + " checkIsNearVehicle — the name-string hook would silently no-op and"
                            + " reintroduce the whole-cell vehicle scan. Re-verify the patch"
                            + " against the current game source.");
        }
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "IsoGameCharacterCheckIsNearVehicleAdvice")
                                        .resolve(),
                                locator)
                        .on(ElementMatchers.named("checkIsNearVehicle")));
    }
}
