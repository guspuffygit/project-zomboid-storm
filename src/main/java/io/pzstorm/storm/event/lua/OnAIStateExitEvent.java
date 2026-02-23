package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.characters.IsoGameCharacter;

/** Triggered when an AI state is exited. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnAIStateExitEvent implements LuaEvent {

    /** The character the state was executed on. */
    public final IsoGameCharacter character;

    public OnAIStateExitEvent(IsoGameCharacter character) {
        this.character = character;
    }
}
