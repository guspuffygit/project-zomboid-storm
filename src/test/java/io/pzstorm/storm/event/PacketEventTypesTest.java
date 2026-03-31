package io.pzstorm.storm.event;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.event.core.PacketEventDispatcher;
import io.pzstorm.storm.event.packet.PacketEvent;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import zombie.core.raknet.UdpConnection;

/**
 * Verifies that every packet class listed in {@link PacketEventDispatcher#SUPPORTED_PACKETS} has a
 * corresponding typed event class in {@code io.pzstorm.storm.event.packet}.
 */
class PacketEventTypesTest implements UnitTest {

    private static final String EVENT_PACKAGE = "io.pzstorm.storm.event.packet.";

    @Test
    void allSupportedPacketsShouldHaveTypedEventClass() {
        List<String> missing = new ArrayList<>();

        for (String fqcn : PacketEventDispatcher.SUPPORTED_PACKETS) {
            String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            String eventClassName = EVENT_PACKAGE + simpleName + "Event";

            try {
                Class.forName(eventClassName);
            } catch (ClassNotFoundException e) {
                missing.add(simpleName + " -> " + eventClassName);
            }
        }

        Assertions.assertTrue(
                missing.isEmpty(),
                "Missing typed event classes for "
                        + missing.size()
                        + " packet(s):\n"
                        + String.join("\n", missing));
    }

    @Test
    void allTypedEventsShouldExtendPacketEvent() {
        List<String> invalid = new ArrayList<>();

        for (String fqcn : PacketEventDispatcher.SUPPORTED_PACKETS) {
            String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            String eventClassName = EVENT_PACKAGE + simpleName + "Event";

            try {
                Class<?> eventClass = Class.forName(eventClassName);
                if (!PacketEvent.class.isAssignableFrom(eventClass)) {
                    invalid.add(eventClassName + " does not extend PacketEvent");
                }
            } catch (ClassNotFoundException e) {
                // Covered by the other test
            }
        }

        Assertions.assertTrue(
                invalid.isEmpty(), "Invalid typed event classes:\n" + String.join("\n", invalid));
    }

    @Test
    void allTypedEventsShouldHaveRequiredConstructor() {
        List<String> invalid = new ArrayList<>();

        for (String fqcn : PacketEventDispatcher.SUPPORTED_PACKETS) {
            String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            String eventClassName = EVENT_PACKAGE + simpleName + "Event";

            try {
                Class<?> eventClass = Class.forName(eventClassName);
                Constructor<?> ctor = eventClass.getConstructor(Object.class, UdpConnection.class);
                if (ctor == null) {
                    invalid.add(eventClassName + " missing constructor(Object, UdpConnection)");
                }
            } catch (ClassNotFoundException e) {
                // Covered by the other test
            } catch (NoSuchMethodException e) {
                invalid.add(eventClassName + " missing constructor(Object, UdpConnection)");
            }
        }

        Assertions.assertTrue(
                invalid.isEmpty(),
                "Event classes with missing constructors:\n" + String.join("\n", invalid));
    }
}
