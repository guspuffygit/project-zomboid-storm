package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.types.HandWeapon;

/**
 * Triggered when a {@link IsoGameCharacter} swings {@link HandWeapon}.
 *
 * @see OnWeaponHitCharacterEvent
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnWeaponSwingEvent implements LuaEvent {

    public final IsoGameCharacter player;
    public final HandWeapon weapon;

    public OnWeaponSwingEvent(IsoGameCharacter player, HandWeapon weapon) {
        this.player = player;
        this.weapon = weapon;
    }
}
