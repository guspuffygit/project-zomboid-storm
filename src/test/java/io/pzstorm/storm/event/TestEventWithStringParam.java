package io.pzstorm.storm.event;

import io.pzstorm.storm.event.core.ZomboidEvent;

/** Test event that requires a String parameter in its constructor. */
public class TestEventWithStringParam implements ZomboidEvent {

    public final String value;

    public TestEventWithStringParam(String value) {
        this.value = value;
    }

    @Override
    public String getName() {
        return "TestEventWithStringParam";
    }
}
