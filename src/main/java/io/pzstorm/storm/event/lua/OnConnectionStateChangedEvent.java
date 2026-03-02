package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/**
 * Called at the different stages that a players connection state changes to the server when
 * initially connecting.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnConnectionStateChangedEvent implements LuaEvent {

    public final String state;
    public final String message;
    public final byte placeInQueue;

    public OnConnectionStateChangedEvent(String state, String message, byte placeInQueue) {
        this.state = state;
        this.message = message;
        this.placeInQueue = placeInQueue;
    }

    public OnConnectionStateChangedEvent(String state, String message) {
        this(state, message, (byte) 0);
    }

    public OnConnectionStateChangedEvent(String state) {
        this(state, "", (byte) 0);
    }
}
