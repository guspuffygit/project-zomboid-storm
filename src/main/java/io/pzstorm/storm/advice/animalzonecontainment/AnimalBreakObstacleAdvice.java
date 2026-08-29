package io.pzstorm.storm.advice.animalzonecontainment;

import io.pzstorm.storm.patch.fixes.AnimalZoneContainment;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the exit of {@code zombie.characters.animals.IsoAnimal
 * .shouldBreakObstaclesDuringPathfinding()} and {@code IsoAnimal.animalShouldThump()}. Both answer
 * "may this animal break through what is in its way"; for an animal inside (or belonging to) a
 * player's animal zone the answer becomes no — see {@link
 * io.pzstorm.storm.patch.fixes.AnimalZoneContainmentPatch} for the rationale.
 *
 * <p>Exit advice rather than a skip on entry so the vanilla bodies still run: {@code
 * animalShouldThump()} advances its own {@code thumpDelay} timer, and leaving that bookkeeping
 * intact means flipping {@code Storm.AnimalZoneContainment} off restores vanilla timing at once.
 *
 * <p>{@code @Advice.This} is typed {@code Object} (not {@code IsoAnimal}) so the inlined call site
 * does not encode a checkcast against a game class — a typed parameter would let javac elide the
 * cast and the JVM verifier would resolve the class at patch registration, before the transformer
 * is in place to apply itself. See the {@code feedback_elided_cast_load} memory.
 */
public class AnimalBreakObstacleAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.This Object animal, @Advice.Return(readOnly = false) boolean result) {
        result = AnimalZoneContainment.allowObstacleBreaking(animal, result);
    }
}
