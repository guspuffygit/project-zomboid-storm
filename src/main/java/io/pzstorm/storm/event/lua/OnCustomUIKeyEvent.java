package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/**
 * Triggers when a unmapped keyboard key is releases. This event is exactly the same as {@link
 * OnCustomUIKeyReleasedEvent}.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnCustomUIKeyEvent implements LuaEvent {

    /**
     * Integer denoting the key being released. See {@code org.lwjgl.input.Keyboard} for list of key
     * codes.
     */
    public final Integer key;

    public OnCustomUIKeyEvent(Integer key) {
        this.key = key;
    }
}
