package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/**
 * Called when key is pressed.
 *
 * @see OnKeyStartPressedEvent
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnKeyPressedEvent implements LuaEvent {

    /**
     * Integer denoting the key being pressed. See {@code org.lwjgl.input.Keyboard} for list of key
     * codes.
     */
    public final Integer key;

    public OnKeyPressedEvent(Integer key) {
        this.key = key;
    }
}
