package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;

/** Called after {@link zombie.Lua.LuaManager#init()} finishes. */
public class OnLuaManagerInitEvent implements ZomboidEvent {

    @Override
    public String getName() {
        return OnLuaManagerInitEvent.class.getName();
    }

    @Override
    public String toString() {
        return getName();
    }
}
