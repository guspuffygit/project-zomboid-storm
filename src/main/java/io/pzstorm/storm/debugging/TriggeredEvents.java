package io.pzstorm.storm.debugging;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.LinkedHashSet;
import java.util.Set;

public class TriggeredEvents {
    private static final Set<String> triggeredEvents = new LinkedHashSet<>();

    public static void add(String event) {
        triggeredEvents.add(event);
    }

    public static void printTriggeredEvents() {
        int index = 1;
        for (String eventName : triggeredEvents) {
            LOGGER.debug("[{}] {}", index++, eventName);
        }
    }
}
