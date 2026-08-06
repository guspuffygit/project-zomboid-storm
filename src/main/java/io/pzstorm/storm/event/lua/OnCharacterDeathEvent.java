package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.characters.IsoGameCharacter;

/**
 * Triggered when {@link IsoGameCharacter} dies. On the server, animals arrive as {@link
 * OnAnimalDeathEvent} and zombies as {@link OnZombieDeathEvent}, leaving players here; on the
 * client (vanilla bytecode) every death still arrives here.
 *
 * @deprecated kept for backwards compatibility only — subscribe to the typed {@code OnDeath} family
 *     instead: {@link OnDeathEvent} (every death), {@link OnPlayerDeathEvent}, {@link
 *     OnZombieDeathEvent} or {@link OnAnimalDeathEvent}.
 */
@Deprecated
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnCharacterDeathEvent implements LuaEvent {

    /** Character that just died. */
    public final IsoGameCharacter character;

    public OnCharacterDeathEvent(IsoGameCharacter character) {
        this.character = character;
    }
}
