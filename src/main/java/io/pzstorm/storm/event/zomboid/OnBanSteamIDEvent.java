package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Dispatched after {@link zombie.network.ServerWorldDatabase#banSteamID(String, String, boolean)}
 * has finished executing. The {@link #result} field contains the string returned by the original
 * method (e.g. {@code "SteamID 76561198000000000 is now banned"}).
 */
@RequiredArgsConstructor
public class OnBanSteamIDEvent implements ZomboidEvent {

    @Getter private final String steamID;
    @Getter private final String reason;
    @Getter private final boolean ban;
    @Getter private final String result;

    @Override
    public String getName() {
        return "OnBanSteamID";
    }
}
