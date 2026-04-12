package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Dispatched after {@link zombie.network.ServerWorldDatabase#banUser(String, boolean)} has finished
 * executing. The {@link #result} field contains the string returned by the original method (e.g.
 * {@code "User \"foo\" is now banned"}).
 */
@RequiredArgsConstructor
public class OnBanUserEvent implements ZomboidEvent {

    @Getter private final String username;
    @Getter private final boolean ban;
    @Getter private final String result;

    @Override
    public String getName() {
        return "OnBanUser";
    }
}
