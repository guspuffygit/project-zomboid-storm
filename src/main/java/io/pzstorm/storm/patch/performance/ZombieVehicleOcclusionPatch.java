package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the vehicle-occlusion scan in the zombie-spots-player path ({@code
 * IsoZombie.isVehicleBetween}) with the guarded, chunk-windowed fast path in {@code
 * ZombieVehicleOcclusion}. Profiling on a loaded server attributed ~14% of main-thread CPU to the
 * vanilla scan (every cell vehicle × two transform inversions per zombie sight test).
 *
 * <p>Two advices: {@code SpottedNewCaptureAdvice} captures {@code spottedNew}'s arguments (the
 * occlusion result is provably unused for some of them), {@code IsoZombieVehicleBetweenAdvice}
 * short-circuits the scan itself. Server-only by registration gate.
 *
 * <p>Kill switch: the {@code Storm.ZombieSightVehicleFastPath} sandbox option ({@code false}
 * restores vanilla behavior; live-appliable via admin sandbox push).
 */
public class ZombieVehicleOcclusionPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.characters.IsoZombie";
    private static final String PKG = "io.pzstorm.storm.advice.zombievehicleocclusion.";

    public ZombieVehicleOcclusionPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        requireDeclared(
                !target.getDeclaredMethods()
                        .filter(ElementMatchers.named("isVehicleBetween"))
                        .isEmpty(),
                "method isVehicleBetween (occlusion fast-path target)");
        requireDeclared(
                !target.getDeclaredMethods().filter(ElementMatchers.named("spottedNew")).isEmpty(),
                "method spottedNew (argument capture target)");
        return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "SpottedNewCaptureAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.named("spottedNew")))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "IsoZombieVehicleBetweenAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.named("isVehicleBetween")));
    }

    private static void requireDeclared(boolean present, String member) {
        if (!present) {
            throw new IllegalStateException(
                    "ZombieVehicleOcclusionPatch: IsoZombie no longer declares "
                            + member
                            + " — the name-string hook would silently no-op and reintroduce the"
                            + " whole-cell vehicle scan. Re-verify the patch against the current"
                            + " game source.");
        }
    }
}
