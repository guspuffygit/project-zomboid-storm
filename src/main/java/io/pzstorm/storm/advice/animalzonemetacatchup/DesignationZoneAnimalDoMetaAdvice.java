package io.pzstorm.storm.advice.animalzonemetacatchup;

import io.pzstorm.storm.patch.fixes.AnimalZoneMetaCatchUp;
import net.bytebuddy.asm.Advice;

/**
 * Enter advice for {@code DesignationZoneAnimal.doMeta(int)}. Runs the single-pass body in {@link
 * AnimalZoneMetaCatchUp} and skips the vanilla body, whose nested baby loop ages each baby once per
 * animal in the zone.
 *
 * <p>{@code @Advice.This} is typed {@code Object} so the inlined call site does not encode a
 * checkcast against the transform target.
 */
public class DesignationZoneAnimalDoMetaAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object zone, @Advice.Argument(0) int hours) {
        AnimalZoneMetaCatchUp.doMeta(zone, hours);
        return true;
    }
}
