package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the body of {@code DesignationZoneAnimal.getAllDZones(ArrayList, DesignationZoneAnimal,
 * DesignationZoneAnimal)} with {@link DesignationZoneAnimalConnectedZones#getAllDZones}, turning
 * the connected-zone flood-fill's per-probe {@code ArrayList.contains} linear scan into a hash
 * lookup. ~1.0 ms of every server tick on ATF (2026-08-30 profile); see the logic class for the
 * full account.
 *
 * <p>Registered server-only alongside the other animal-zone patches: the callers that matter
 * ({@code IsoAnimal.update}'s zone re-check, {@code check()}, the trough/hutch/corpse queries) are
 * server-authoritative in MP.
 */
public class DesignationZoneAnimalGetAllDZonesPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.dzoneconnected.DesignationZoneAnimalGetAllDZonesAdvice";

    public DesignationZoneAnimalGetAllDZonesPatch() {
        super("zombie.iso.areas.DesignationZoneAnimal");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("getAllDZones")
                                        .and(ElementMatchers.isStatic())
                                        .and(ElementMatchers.takesArguments(3))));
    }
}
