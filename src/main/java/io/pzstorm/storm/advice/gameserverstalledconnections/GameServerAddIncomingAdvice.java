package io.pzstorm.storm.advice.gameserverstalledconnections;

import net.bytebuddy.asm.Advice;
import zombie.core.raknet.UdpConnection;

/**
 * Stamps the connection as alive on every inbound game packet, feeding {@link
 * StalledConnectionReaper}. Runs on the {@code UdpEngine} thread; {@link
 * StalledConnectionReaper#recordActivity(UdpConnection)} is allocation-free and returns immediately
 * for fully-connected clients, which are never reaped.
 */
public class GameServerAddIncomingAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(2) UdpConnection connection) {
        StalledConnectionReaper.recordActivity(connection);
    }
}
