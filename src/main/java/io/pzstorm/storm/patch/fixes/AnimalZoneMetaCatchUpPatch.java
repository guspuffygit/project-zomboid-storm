package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the body of {@code zombie.iso.areas.DesignationZoneAnimal.doMeta(int)} with the
 * single-pass catch-up in {@link AnimalZoneMetaCatchUp}.
 *
 * <h2>The bug this patches</h2>
 *
 * <p>{@code doMeta} runs when a zone's chunks stream back in and advances every animal by the hours
 * nobody was near it. 42.22 rewrote the method over a {@code HashSet} and left the baby pass inside
 * an extra {@code for (i < size)} loop, so each baby calls {@code updateStatsAway(hours)} once per
 * animal in the zone. Each call adds {@code hours / 24} to the age, walks the calendar forward from
 * where the previous call left it, and rolls {@code hourGrow}, {@code growUp} and {@code
 * checkKilledByMetaPredator} for every hour, so a baby in a pen of N animals ages N times as fast
 * and faces N times the predator rolls on every return to the pen. 42.21 iterated once.
 *
 * <h2>Why a body replacement</h2>
 *
 * <p>The extra loop is a statement in the middle of the method, so a boundary advice cannot drop
 * it. The body is four loops over public members ({@code check()}, {@code getAnimalsInZone()},
 * {@code hutchs}), so the replacement is the vanilla method with the outer baby loop removed.
 *
 * <h2>Registration</h2>
 *
 * <p>Gated server-only in {@code StormClassTransformers}. Zone streaming and {@code
 * updateStatsAway} are server-authoritative in MP.
 */
public class AnimalZoneMetaCatchUpPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.animalzonemetacatchup.DesignationZoneAnimalDoMetaAdvice";

    public AnimalZoneMetaCatchUpPatch() {
        super("zombie.iso.areas.DesignationZoneAnimal");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("doMeta")
                                        .and(ElementMatchers.takesArguments(int.class))));
    }
}
