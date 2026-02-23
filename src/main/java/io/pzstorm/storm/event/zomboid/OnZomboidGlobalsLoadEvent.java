package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;
import zombie.ZomboidGlobals;

/** Called after {@link ZomboidGlobals#Load()} */
public class OnZomboidGlobalsLoadEvent implements ZomboidEvent {

    @Override
    public String getName() {
        return OnZomboidGlobalsLoadEvent.class.getName();
    }

    @Override
    public String toString() {
        return getName();
    }
}
