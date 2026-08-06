package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.characters.IsoZombie;

/**
 * Triggered when an {@link IsoZombie} dies. Storm-added, server-only: fired from the {@link
 * OnDeathEvent} trigger, so on the client (vanilla bytecode) zombies still arrive as {@link
 * OnCharacterDeathEvent}. Distinct from vanilla's {@link OnZombieDeadEvent}, which fires earlier in
 * {@code IsoZombie.die()} (and can repeat on fire deaths); this one fires exactly once per death,
 * alongside the rest of the {@code OnDeath} family.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnZombieDeathEvent implements LuaEvent {

    /** Zombie that just died. */
    public final IsoZombie zombie;

    public OnZombieDeathEvent(IsoZombie zombie) {
        this.zombie = zombie;
    }
}
