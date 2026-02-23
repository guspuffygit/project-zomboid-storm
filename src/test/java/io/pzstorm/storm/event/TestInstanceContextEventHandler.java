package io.pzstorm.storm.event;

import io.pzstorm.storm.event.core.SubscribeEvent;

@SuppressWarnings("unused")
public class TestInstanceContextEventHandler {

    final Boolean[] eventsCalled = new Boolean[] {false, false};

    @SubscribeEvent
    public void handleTestZomboidEventA(TestZomboidEventA event) {
        eventsCalled[0] = true;
    }

    @SubscribeEvent
    public void handleTestZomboidEventB(TestZomboidEventB event) {
        eventsCalled[1] = true;
    }
}
