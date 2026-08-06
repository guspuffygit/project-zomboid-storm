package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.characters.animals.IsoAnimal;

/**
 * Triggered when an {@link IsoAnimal} dies. Storm-added, server-only: fired from the {@link
 * OnDeathEvent} trigger, so on the client (vanilla bytecode) animals still arrive as {@link
 * OnCharacterDeathEvent}. Tested before zombies and players in the split because {@code IsoAnimal}
 * extends {@code IsoPlayer}.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnAnimalDeathEvent implements LuaEvent {

    /** Animal that just died. */
    public final IsoAnimal animal;

    public OnAnimalDeathEvent(IsoAnimal animal) {
        this.animal = animal;
    }
}
