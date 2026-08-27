package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Performance patch for {@code IsoGenerator.update()}.
 *
 * <p>On a dedicated server, the per-tick chunk-load triggered call to {@code
 * setSurroundingElectricity()} dominates main-thread CPU. In a 60-second JFR capture with 77
 * players it accounted for ~22% of all execution samples. The expensive part &mdash; a (2R+1)&sup2;
 * &times; Z grid-square scan with 11 instanceof checks per IsoObject &mdash; produces only data
 * that the server doesn't read per tick:
 *
 * <ul>
 *   <li>{@code itemsPowered} is consumed only by client UI ({@code ISGeneratorInfoWindow.lua}).
 *   <li>{@code totalPowerUsing} is consumed only by the hourly fuel-consumption loop and is
 *       refreshed whenever the generator is toggled (those call sites stay on the full path).
 *   <li>{@code IsoObject.checkHaveElectricity()} (called per object in the scan) is already a no-op
 *       on a server &mdash; it bails on the first line.
 * </ul>
 *
 * <p>The patch replaces the per-tick path with just the chunk-position bookkeeping ({@code
 * IsoChunk.addGeneratorPos / removeGeneratorPos}) that powers {@code
 * IsoGridSquare.haveElectricity()}, then clears the {@code updateSurrounding} flag so the original
 * method skips its expensive call. Activation-state callers ({@code setActivated}, {@code
 * syncIsoObjectReceive}) are untouched and still trigger a full scan when the generator is turned
 * on/off.
 *
 * <p>{@code totalPowerUsing} is restored on load from modData {@code "totalPowerDraw"} only when
 * that key is present &mdash; older saves and freshly placed generators start at {@code 0.0F}.
 * Because it's also the multiplier the hourly fuel-drain loop uses, an uninitialized value means
 * fuel never decreases. The advice guards against this by letting the original {@code
 * setSurroundingElectricity()} run once when {@code totalPowerUsing <= 0}; subsequent ticks (where
 * it's at the {@code 0.02F} baseline or higher) take the fast path.
 *
 * <p>The redefinition also adds a {@code stormInactiveSwept} boolean (surfaced through {@link
 * io.pzstorm.storm.entity.StormGeneratorSweptFlag}): {@code IsoChunk.chunkLoaded} re-flags {@code
 * updateSurrounding} on every touching generator whenever a nearby chunk loads, which made the fast
 * path re-run its full removal loop for inactive generators over and over (~0.8% of server main on
 * ATF, 2026-08-26 profile). One removal sweep per deactivation is enough — chunks loaded afterwards
 * are cleaned by vanilla {@code IsoChunk.checkForMissingGenerators()} at load time — so the advice
 * latches after an inactive sweep and short-circuits until the generator is activated again.
 *
 * <p>Advice loaded via {@code typePool.describe().resolve()} (ASM-only parsing) so Byte Buddy
 * doesn't trigger class loading of the transform target.
 */
public class IsoGeneratorElectricityPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isogeneratorelectricity.";

    public IsoGeneratorElectricityPatch() {
        super("zombie.iso.objects.IsoGenerator");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField("stormInactiveSwept", boolean.class, Visibility.PUBLIC)
                .implement(
                        typePool.describe("io.pzstorm.storm.entity.StormGeneratorSweptFlag")
                                .resolve())
                .intercept(FieldAccessor.ofField("stormInactiveSwept"))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "SkipServerScanAdvice").resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("update")
                                                .and(ElementMatchers.takesArguments(0))));
    }
}
