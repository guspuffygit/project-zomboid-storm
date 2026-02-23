package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.iso.IsoWorld;
import zombie.iso.sprite.IsoSpriteManager;

/** Triggered after tile definitions for {@link IsoWorld} have been loaded. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnLoadedTileDefinitionsEvent implements LuaEvent {

    /** Manager used to load tile definitions. */
    public final IsoSpriteManager spriteManager;

    public OnLoadedTileDefinitionsEvent(IsoSpriteManager spriteManager) {
        this.spriteManager = spriteManager;
    }
}
