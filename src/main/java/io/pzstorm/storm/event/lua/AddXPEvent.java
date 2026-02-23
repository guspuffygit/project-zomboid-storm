package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.characters.IsoGameCharacter;
import zombie.characters.skills.PerkFactory;

/** Triggered when {@link IsoGameCharacter} earns skill experience. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class AddXPEvent implements LuaEvent {

    /** Game character earning experience. */
    public final IsoGameCharacter player;

    /** Perk to earn experience for. */
    public final PerkFactory.Perk perk;

    /** Amount of experience to earn. */
    public final Float xpAmount;

    public AddXPEvent(IsoGameCharacter player, PerkFactory.Perk perk, Float xpAmount) {
        this.player = player;
        this.perk = perk;
        this.xpAmount = xpAmount;
    }
}
