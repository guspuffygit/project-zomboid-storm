package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/**
 * Triggers when a unmapped keyboard key is releases. This event is exactly the same as {@link
 * OnCustomUIKeyEvent}.
 *
 * @see OnCustomUIKeyPressedEvent
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnCustomUIKeyReleasedEvent implements LuaEvent {

    /**
     * Integer denoting the key being released. See {@code org.lwjgl.input.Keyboard} for list of key
     * codes.
     */
    public final Integer key;

    public OnCustomUIKeyReleasedEvent(Integer key) {
        this.key = key;
    }
}
