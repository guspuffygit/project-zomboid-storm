package io.pzstorm.storm.event;

import io.pzstorm.storm.event.core.ZomboidEvent;

public class TestZomboidEventB implements ZomboidEvent {

    @Override
    public String getName() {
        return "zomboidEventB";
    }
}
