package io.pzstorm.storm.advice.ignoredtroughexpiry;

import io.pzstorm.storm.patch.fixes.AnimalIgnoredTroughExpiry;
import net.bytebuddy.asm.Advice;

/**
 * Inlined around the private {@code zombie.characters.animals.IsoAnimal.checkZone()}. Vanilla's
 * body early-returns while {@code zoneCheckTimer} is still counting down, so the entry advice
 * samples the timer to tell a real zone check from a countdown tick, and the exit advice clears the
 * animal's trough blacklist only after a real check — see {@link
 * io.pzstorm.storm.patch.fixes.AnimalIgnoredTroughExpiryPatch}.
 *
 * <p>{@code @Advice.This} is typed {@code Object} so the inlined call site does not encode a
 * checkcast against a game class.
 */
public class IgnoredTroughExpiryAdvice {

    @Advice.OnMethodEnter
    public static boolean onEnter(@Advice.FieldValue("zoneCheckTimer") float zoneCheckTimer) {
        return zoneCheckTimer <= 0.0F;
    }

    @Advice.OnMethodExit
    public static void onExit(@Advice.This Object animal, @Advice.Enter boolean zoneChecked) {
        if (zoneChecked) {
            AnimalIgnoredTroughExpiry.onZoneCheck(animal);
        }
    }
}
