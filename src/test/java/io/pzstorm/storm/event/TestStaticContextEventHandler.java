package io.pzstorm.storm.event;

import io.pzstorm.storm.event.core.SubscribeEvent;

@SuppressWarnings("unused")
class TestStaticContextEventHandler {

    static final Boolean[] eventsCalled = new Boolean[] {false, false};

    @SubscribeEvent
    public static void handleTestZomboidEventA(TestZomboidEventA event) {
        eventsCalled[0] = true;
    }

    @SubscribeEvent
    public static void handleTestZomboidEventB(TestZomboidEventB event) {
        eventsCalled[1] = true;
    }
}
