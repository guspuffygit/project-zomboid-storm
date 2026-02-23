package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/**
 * Triggers when an unmapped keyboard key is pressed.
 *
 * @see OnCustomUIKeyReleasedEvent
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnCustomUIKeyPressedEvent implements LuaEvent {

    /**
     * Integer denoting the key being pressed. See {@code org.lwjgl.input.Keyboard} for list of key
     * codes.
     */
    public final Integer key;

    public OnCustomUIKeyPressedEvent(Integer key) {
        this.key = key;
    }
}
