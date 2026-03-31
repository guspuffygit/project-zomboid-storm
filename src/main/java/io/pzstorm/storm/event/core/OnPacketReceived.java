package io.pzstorm.storm.event.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation placed on handler methods that subscribe to packet events for a specific packet type.
 * Methods annotated with this must have exactly one parameter of type {@link
 * io.pzstorm.storm.event.zomboid.OnPacketReceivedEvent}.
 *
 * <p>The {@link #value()} specifies the simple class name of the packet to subscribe to (e.g.
 * {@code "ItemTransactionPacket"}).
 *
 * @see PacketEventDispatcher
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnPacketReceived {

    /** Simple class name of the packet type to subscribe to. */
    String value();
}
