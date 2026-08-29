package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.characters.animals.IsoAnimal;

/**
 * Attaches an entry advice to {@code zombie.iso.objects.IsoHutch.addAnimalInside(IsoAnimal,
 * boolean)} that re-homes an origin-positioned animal to the hutch's coordinates before it is
 * stored. The actual fix logic lives in {@link CoopHatchPositionFix}.
 *
 * <h2>The bug this patches</h2>
 *
 * <p>{@code Food.checkEggHatch(IsoHutch)} never assigns x/y/z when the egg hatches inside a hutch —
 * the position resolution and the {@code x == 0 && y == 0} sanity guard are both scoped inside its
 * {@code hutch == null} branch. Every coop-hatched chick is therefore born at the map origin {@code
 * (0.5, 0.5, 0.0)}. Because animal sync is relevancy-gated on the animal's coordinates, an origin
 * chick never syncs to its owner (the coop looks empty — the "my chicks are gone" report) and the
 * bad coordinate persists through the boxed-as-item state and across restarts. See {@link
 * CoopHatchPositionFix} for the full write-up and live numbers.
 *
 * <h2>Why {@code addAnimalInside} and not {@code checkEggHatch}</h2>
 *
 * <p>{@code addAnimalInside(IsoAnimal, boolean)} is the single funnel every animal passes through
 * on its way into a hutch: the hatch path calls it immediately after constructing the chick, and
 * {@code IsoHutch.load} calls it for every saved occupant — so the patch both prevents new origin
 * chicks and heals ones already saved at origin while still inside a coop, the moment their hutch
 * loads. Fixing {@code checkEggHatch} itself would require rewriting mid-method locals rather than
 * a method-boundary advice.
 *
 * <h2>Registration</h2>
 *
 * <p>Gated server-only in {@code StormClassTransformers} — hutch hatching and animal membership are
 * server-authoritative in MP; clients mirror via {@code sendAnimalUpdate}.
 */
public class CoopHatchPositionFixPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.coophatchposition.";

    public CoopHatchPositionFixPatch() {
        super("zombie.iso.objects.IsoHutch");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "CoopHatchPositionAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("addAnimalInside")
                                        .and(
                                                ElementMatchers.takesArguments(
                                                        IsoAnimal.class, boolean.class))
                                        .and(ElementMatchers.returns(boolean.class))));
    }
}
