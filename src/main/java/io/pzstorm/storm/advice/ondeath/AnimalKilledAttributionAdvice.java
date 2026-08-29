package io.pzstorm.storm.advice.ondeath;

import net.bytebuddy.asm.Advice;

/**
 * Inlined at the head of {@code IsoAnimal.killed(IsoPlayer)} to stamp {@code attackedBy} for the
 * Lua slaughter action ({@code ISKillAnimal}: {@code setHealth(0)} + {@code killed(chr)}), so the
 * state-machine death that follows — {@code die()} → {@code Kill(getAttackedBy())} — attributes the
 * kill instead of arriving with a null killer. No event is fired here; the normal {@code OnDeath}
 * path handles that.
 */
public class AnimalKilledAttributionAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This Object self, @Advice.Argument(0) Object chr) {
        AnimalDeathEvents.attributeKill(self, chr);
    }
}
