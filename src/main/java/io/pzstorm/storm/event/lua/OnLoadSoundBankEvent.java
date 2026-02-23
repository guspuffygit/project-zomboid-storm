package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/**
 * This event triggers when a sound bank is being loaded. Users can use this event to change the
 * path of sound banks being loaded, effectively replacing vanilla with their own custom sound
 * banks.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnLoadSoundBankEvent implements LuaEvent {

    /**
     * {@code StringBuffer} containing the string that denotes the path to the sound bank being
     * loaded. In order to replace the given path with a custom path you can do the following:
     *
     * <pre>
     * String customPath = "path/to/custom/sound/bank";
     * soundBankPath.delete(0, soundBankPath.length()).append(customPath);
     * </pre>
     */
    public final StringBuffer soundBankPath;

    public OnLoadSoundBankEvent(StringBuffer soundBankPath) {
        this.soundBankPath = soundBankPath;
    }
}
