package io.pzstorm.storm.advice.ondeath;

import net.bytebuddy.asm.Advice;
import zombie.Lua.LuaEventManager;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;

/**
 * Replaces the body of {@code OnDeath()} — a single {@code triggerEvent("OnCharacterDeath", this)}
 * call in both {@code IsoGameCharacter} and its copy-paste {@code IsoAnimal} override — with a
 * Storm-added {@code OnDeath} trigger for every death, followed by a per-type trigger: {@code
 * OnAnimalDeath} for animals, {@code OnZombieDeath} for zombies and {@code OnPlayerDeath} for
 * players. The animal test runs first because {@code IsoAnimal} extends {@code IsoPlayer}.
 *
 * <p>Players (and any future {@code IsoGameCharacter} subtype, preserving vanilla's catch-all)
 * additionally fire the deprecated {@code OnCharacterDeath} for backwards compatibility.
 */
public class OnDeathAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object self) {
        LuaEventManager.triggerEvent("OnDeath", self);
        if (self instanceof IsoAnimal) {
            LuaEventManager.triggerEvent("OnAnimalDeath", self);
        } else if (self instanceof IsoZombie) {
            LuaEventManager.triggerEvent("OnZombieDeath", self);
        } else {
            if (self instanceof IsoPlayer) {
                LuaEventManager.triggerEvent("OnPlayerDeath", self);
            }
            LuaEventManager.triggerEvent("OnCharacterDeath", self);
        }
        return true;
    }
}
