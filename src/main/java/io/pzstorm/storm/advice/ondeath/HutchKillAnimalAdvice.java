package io.pzstorm.storm.advice.ondeath;

import net.bytebuddy.asm.Advice;

/**
 * Inlined at the head of {@code zombie.iso.objects.IsoHutch.killAnimal(IsoAnimal)}, the only death
 * path that never reaches {@code OnDeath()}: meta-predator kills ({@code
 * IsoAnimal.checkKilledByMetaPredator}) and in-hutch health-drain deaths both set health to 0 and
 * construct the {@code IsoDeadBody} directly.
 *
 * <p>Fires on entry, before {@code setHealth(0)}, so handlers see pre-kill vitals: a meta-predator
 * victim still has health &gt; 0 while a health-drain death arrives at &le; 0 — the only way to
 * tell the two causes apart.
 */
public class HutchKillAnimalAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Object animal) {
        AnimalDeathEvents.triggerOnce(animal);
    }
}
