package io.pzstorm.storm.advice.animalzonecontainment;

import io.pzstorm.storm.patch.fixes.AnimalZoneContainment;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the entry of {@code zombie.characters.animals.IsoAnimal.pathToLocation(int, int,
 * int)}: rewrites the target of a contained animal's path so it stays inside the animal zone the
 * animal belongs to, and walks a stray back toward it.
 *
 * <p>Clamping here rather than in {@code BaseAnimalBehavior.wanderIdle()} covers every caller that
 * picks a destination for the animal with one boundary advice, instead of an in-body rewrite of
 * vanilla's re-roll loop — see {@link io.pzstorm.storm.patch.fixes.AnimalZoneContainmentPatch}.
 *
 * <p>The two coordinates come back packed in a single long so the inlined advice needs no
 * allocation and no second helper call.
 */
public class AnimalPathToLocationAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.This Object animal,
            @Advice.Argument(value = 0, readOnly = false) int x,
            @Advice.Argument(value = 1, readOnly = false) int y,
            @Advice.Argument(2) int z) {
        long clamped = AnimalZoneContainment.clampTarget(animal, x, y, z);
        if (clamped != AnimalZoneContainment.NO_CLAMP) {
            x = AnimalZoneContainment.unpackX(clamped);
            y = AnimalZoneContainment.unpackY(clamped);
        }
    }
}
