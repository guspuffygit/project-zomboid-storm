package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoMovingObject;

/** Triggered when experience is earned by attacking with weapon. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnWeaponHitXpEvent implements LuaEvent {

    /** Player earning experience. */
    public final IsoGameCharacter player;

    /** Weapon used to attack. */
    public final HandWeapon weapon;

    /** Object being hit with weapon. */
    public final IsoMovingObject target;

    /** Either amount of damage or experience earned. */
    public final Float value;

    public final Integer intValue;

    public OnWeaponHitXpEvent(
            IsoGameCharacter player,
            HandWeapon weapon,
            IsoMovingObject target,
            Float value,
            Integer intValue) {
        this.player = player;
        this.weapon = weapon;
        this.target = target;
        this.value = value;
        this.intValue = intValue;
    }
}
