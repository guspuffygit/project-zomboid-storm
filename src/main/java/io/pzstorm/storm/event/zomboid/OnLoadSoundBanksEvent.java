package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;

/**
 * This event triggers after vanilla FMOD sound banks finish loading. You can use this event to load
 * custom sound banks like this:
 *
 * <pre>
 * String path = "path/to/sound/bank";
 * javafmod.FMOD_Studio_System_LoadBankFile(path);
 * </pre>
 */
public class OnLoadSoundBanksEvent implements ZomboidEvent {

    @Override
    public String getName() {
        return "OnLoadSoundBanks";
    }
}
