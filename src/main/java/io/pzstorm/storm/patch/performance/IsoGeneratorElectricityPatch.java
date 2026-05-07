package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
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
 * <p>{@code totalPowerUsing} is not serialized in {@code save()/load()} &mdash; it defaults to
 * {@code 0.0F} on every world load. Because it's also the multiplier the hourly fuel-drain loop
 * uses, an uninitialized value means fuel never decreases. The advice guards against this by
 * letting the original {@code setSurroundingElectricity()} run once when {@code totalPowerUsing
 * <= 0}; subsequent ticks (where it's at the {@code 0.02F} baseline or higher) take the fast
 * path.
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
        return builder.visit(
                Advice.to(typePool.describe(PKG + "SkipServerScanAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("update")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
