package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Triggered when ZomboidFileSystem.loadMods(ArrayList) is called. */
@RequiredArgsConstructor
public class OnLoadModsEvent implements ZomboidEvent {

    @Getter private final List<String> activeMods;

    @Override
    public String getName() {
        return "OnLoadMods";
    }
}
