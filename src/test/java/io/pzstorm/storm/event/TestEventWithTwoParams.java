package io.pzstorm.storm.event;

import io.pzstorm.storm.event.core.ZomboidEvent;

/** Test event that requires a String and Integer parameter in its constructor. */
public class TestEventWithTwoParams implements ZomboidEvent {

    public final String name;
    public final Integer count;

    public TestEventWithTwoParams(String name, Integer count) {
        this.name = name;
        this.count = count;
    }

    @Override
    public String getName() {
        return "TestEventWithTwoParams";
    }
}
