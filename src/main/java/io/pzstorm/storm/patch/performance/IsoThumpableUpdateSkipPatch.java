package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Early-returns {@code IsoThumpable.update()} on a dedicated server when the object has no
 * fuel-driven life ({@code getLifeLeft() <= -1.0F}, the constructor default). Player-built walls,
 * doors, fences and barricades dominate {@code IsoCell.processIsoObject}: profiling on ATF
 * production (2026-08-24, 95 players) found 50,219 of 56,595 processed objects were {@code
 * IsoThumpable}, of which 8 (lit fuel lanterns) had {@code lifeLeft > -1} — yet the vanilla method
 * opens with {@code getObjectIndex()}, a linear {@code PZArrayList.indexOf} over the square's
 * object list through a per-element comparator lambda, costing 7.26% of the main thread (~4.6 ms of
 * a ~62 ms tick) to do provably nothing.
 *
 * <p>Equivalence on the server: the light-source section is inside {@code if (!GameServer.server)}
 * and the only other statement is the {@code getLifeLeft() > -1.0F} branch, so for a skipped object
 * the vanilla body has no effect regardless of the {@code getObjectIndex() != -1} outcome; objects
 * with {@code lifeLeft > -1} run the untouched vanilla body. Server-only by registration gate.
 *
 * <p>Re-validate on game update: {@code update()} must keep the shape {@code if (getObjectIndex()
 * != -1) { if (!GameServer.server) {light sources} if (getLifeLeft() > -1.0F) {fuel burn-down} }}
 * with no other server-reachable side effects (IsoThumpable.java:1498 in 42.20.3).
 */
public class IsoThumpableUpdateSkipPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.objects.IsoThumpable";
    private static final String PKG = "io.pzstorm.storm.advice.thumpableupdate.";

    public IsoThumpableUpdateSkipPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(ElementMatchers.named("update").and(ElementMatchers.takesArguments(0)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "IsoThumpableUpdateSkipPatch: IsoThumpable no longer declares update() — the"
                            + " name-string hook would silently no-op and reintroduce the per-tick"
                            + " getObjectIndex() scan for every thumpable. Re-verify the patch"
                            + " against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(PKG + "IsoThumpableUpdateAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("update")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
