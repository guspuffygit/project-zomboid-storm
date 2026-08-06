package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.characters.IsoGameCharacter;

/**
 * Triggered when any {@link IsoGameCharacter} dies — players, zombies and animals. Storm-added,
 * server-only: {@code OnDeathTriggerPatch} fires it in place of vanilla's blanket {@code
 * OnCharacterDeath}, then re-triggers the per-type event: {@link OnAnimalDeathEvent}, {@link
 * OnZombieDeathEvent} or {@link OnPlayerDeathEvent}. Players (plus any future subtype, as the
 * catch-all) also fire the deprecated {@link OnCharacterDeathEvent} for backwards compatibility.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnDeathEvent implements LuaEvent {

    /** Character that just died. */
    public final IsoGameCharacter character;

    public OnDeathEvent(IsoGameCharacter character) {
        this.character = character;
    }
}
