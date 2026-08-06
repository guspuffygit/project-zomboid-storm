package io.pzstorm.storm.advice.ondeath;

import net.bytebuddy.asm.Advice;
import zombie.Lua.LuaEventManager;
import zombie.characters.animals.IsoAnimal;

/**
 * Replaces the body of {@code OnDeath()} — a single {@code triggerEvent("OnCharacterDeath", this)}
 * call in both {@code IsoGameCharacter} and its copy-paste {@code IsoAnimal} override — with a
 * Storm-added {@code OnDeath} trigger for every death, followed by {@code OnAnimalDeath} for
 * animals and vanilla's {@code OnCharacterDeath} for everyone else.
 */
public class OnDeathAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object self) {
        LuaEventManager.triggerEvent("OnDeath", self);
        if (self instanceof IsoAnimal) {
            LuaEventManager.triggerEvent("OnAnimalDeath", self);
        } else {
            LuaEventManager.triggerEvent("OnCharacterDeath", self);
        }
        return true;
    }
}
