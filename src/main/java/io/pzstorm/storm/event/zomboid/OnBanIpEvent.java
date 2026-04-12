package io.pzstorm.storm.event.zomboid;

import io.pzstorm.storm.event.core.ZomboidEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Dispatched after {@link zombie.network.ServerWorldDatabase#banIp(String, String, String,
 * boolean)} has finished executing. The {@link #result} field contains the string returned by the
 * original method (e.g. {@code "IP 1.2.3.4(foo) is now banned"}).
 */
@RequiredArgsConstructor
public class OnBanIpEvent implements ZomboidEvent {

    @Getter private final String ip;
    @Getter private final String username;
    @Getter private final String reason;
    @Getter private final boolean ban;
    @Getter private final String result;

    @Override
    public String getName() {
        return "OnBanIp";
    }
}
