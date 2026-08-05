package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the server-side body of {@code IsoPlayer.updateLOS()} with the distance-culled,
 * server-stripped fast path in {@code StormPlayerLos}. Live profiling (79 players) attributed ~18%
 * of main-thread CPU to this method — a per-player, per-tick walk of every moving object in the
 * loaded cell (~4,000) driving ~2.5M {@code ServerLOS.isCouldSee} lookups/second plus client-only
 * bookkeeping that is not {@code bServer}-gated.
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}) — {@code IsoPlayer} is
 * loaded by clients too. Must stay registered <em>before</em> {@code IsoPlayerUpdateLOSPatch} so
 * the stopwatch advice wraps the skip and keeps timing both paths.
 *
 * <p>Kill switch: the {@code Storm.PlayerLosFastPath} sandbox option ({@code false} restores the
 * vanilla loop; live-appliable via admin sandbox push). The fast path also permanently reverts to
 * vanilla if it ever throws.
 */
public class IsoPlayerUpdateLOSFastPathPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.characters.IsoPlayer";
    private static final String PKG = "io.pzstorm.storm.advice.playerlosfastpath.";

    public IsoPlayerUpdateLOSFastPathPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(ElementMatchers.named("updateLOS").and(ElementMatchers.takesArguments(0)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "IsoPlayerUpdateLOSFastPathPatch: IsoPlayer no longer declares updateLOS() —"
                            + " the name-string hook would silently no-op and reintroduce the"
                            + " whole-cell moving-object walk. Re-verify the patch against the"
                            + " current game source.");
        }
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "IsoPlayerUpdateLOSFastPathAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("updateLOS")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
