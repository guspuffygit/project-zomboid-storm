package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.characters.IsoPlayer;

/**
 * Triggered when an {@link IsoPlayer} dies. Fires in two distinct situations: vanilla triggers it
 * on the client for the local player only ({@code IsoPlayer.OnDeath()}), and on the Storm server
 * {@code OnDeathTriggerPatch} triggers it for every player death as part of the {@link
 * OnDeathEvent} family. Animals never arrive here despite {@code IsoAnimal} extending {@code
 * IsoPlayer} — they're split onto {@link OnAnimalDeathEvent} before the player test.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnPlayerDeathEvent implements LuaEvent {

    /** The player which died. */
    public final IsoPlayer player;

    public OnPlayerDeathEvent(IsoPlayer player) {
        this.player = player;
    }
}
