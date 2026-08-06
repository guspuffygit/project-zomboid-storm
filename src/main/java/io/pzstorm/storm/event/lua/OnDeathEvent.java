package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.characters.IsoGameCharacter;

/**
 * Triggered when any {@link IsoGameCharacter} dies — players, zombies, NPC survivors and animals.
 * Storm-added, server-only: {@code OnDeathTriggerPatch} fires it in place of vanilla's blanket
 * {@code OnCharacterDeath}, then re-triggers {@link OnAnimalDeathEvent} for animals and {@link
 * OnCharacterDeathEvent} for everyone else.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnDeathEvent implements LuaEvent {

    /** Character that just died. */
    public final IsoGameCharacter character;

    public OnDeathEvent(IsoGameCharacter character) {
        this.character = character;
    }
}
