package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Attaches enter/exit advice to the private {@code zombie.characters.animals.IsoAnimal.checkZone()}
 * that clears the animal's {@code ignoredTrough} blacklist whenever the zone check actually runs.
 * The fix logic lives in {@link AnimalIgnoredTroughExpiry}.
 *
 * <h2>The bug this patches</h2>
 *
 * <p>Vanilla only ever clears {@code ignoredTrough} in {@code IsoAnimal.removeFromWorld}. Every
 * failed trough approach — arriving farther than {@code distToEat}, a failed path, an occupied
 * standing square — adds the trough permanently for the life of the loaded animal, so on a busy
 * server one bad approach means the animal dies of thirst or hunger beside a stocked trough.
 *
 * <h2>Why {@code checkZone()}</h2>
 *
 * <p>It is the vanilla cadence at which the animal rebuilds its connected zones, and with them the
 * trough candidate list — the natural moment to give every trough another chance. The method
 * early-returns while its timer counts down, so the advice samples {@code zoneCheckTimer} on entry
 * and only acts on exit from a pass that ran the check. Vanilla's own {@code updateStatsAway}
 * zeroes the timer before calling it, so an animal returning from the metagame also starts with a
 * clean slate.
 *
 * <h2>Registration</h2>
 *
 * <p>Gated server-only in {@code StormClassTransformers} — {@code checkZone()} is only called from
 * the {@code !GameClient.client} branch of {@code updateInternal} and from the server-side metagame
 * return. A throw in the fix latches it off permanently; vanilla's permanent blacklist resumes.
 */
public class AnimalIgnoredTroughExpiryPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.ignoredtroughexpiry.";

    public AnimalIgnoredTroughExpiryPatch() {
        super("zombie.characters.animals.IsoAnimal");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "IgnoredTroughExpiryAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("checkZone")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
