package io.pzstorm.storm.advice.ondeath;

import net.bytebuddy.asm.Advice;

/**
 * Inlined at the head of {@code IsoGameCharacter.doDeathSplatterAndSounds(HandWeapon,
 * IsoGameCharacter, boolean)}. Its only callers are {@code DoDeath()} (which fired {@code
 * OnDeath()} moments earlier — dedup makes this a no-op) and the Lua inventory-kill action {@code
 * ISKillAnimalInInventory}, which kills a carried animal and corpse-ifies it without ever touching
 * {@code DoDeath} — the path this advice exists to catch. Non-animals fall out of the helper's
 * instanceof gate immediately.
 */
public class AnimalDeathSplatterAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This Object self, @Advice.Argument(1) Object wielder) {
        AnimalDeathEvents.triggerOnceWithKiller(self, wielder);
    }
}
