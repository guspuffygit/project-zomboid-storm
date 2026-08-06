package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.characters.IsoGameCharacter;

/**
 * Triggered when {@link IsoGameCharacter} dies. On the server, animals arrive as {@link
 * OnAnimalDeathEvent} instead; on the client (vanilla bytecode) they still arrive here.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnCharacterDeathEvent implements LuaEvent {

    /** Character that just died. */
    public final IsoGameCharacter character;

    public OnCharacterDeathEvent(IsoGameCharacter character) {
        this.character = character;
    }
}
