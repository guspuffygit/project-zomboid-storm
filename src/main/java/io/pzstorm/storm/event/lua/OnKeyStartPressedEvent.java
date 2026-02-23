package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/**
 * Called when key is initially pressed.
 *
 * @see OnKeyPressedEvent
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnKeyStartPressedEvent implements LuaEvent {

    /**
     * Integer denoting the key being pressed. See {@code org.lwjgl.input.Keyboard} for list of key
     * codes.
     */
    public final Integer key;

    public OnKeyStartPressedEvent(Integer key) {
        this.key = key;
    }
}
