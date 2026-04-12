package io.pzstorm.storm.advice.packet;

import io.pzstorm.storm.patch.fixes.NtaDebugLog;
import net.bytebuddy.asm.Advice;
import zombie.core.raknet.UdpConnection;
import zombie.network.PacketTypes;

/**
 * Advice for {@code PacketTypes.PacketType.onServerPacket()}. Logs every packet received by the
 * server, filtered to our player's connection.
 */
public class OnServerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This PacketTypes.PacketType self,
            @Advice.Argument(1) UdpConnection connection) {
        if (!NtaDebugLog.isAllowedConnection(connection)) return;

        String user = connection.getUserName() != null ? connection.getUserName() : "?";
        NtaDebugLog.log(
                "SERVER",
                "PACKET-RECV type="
                        + self.name()
                        + " from="
                        + user
                        + " steamId="
                        + connection.getSteamId());
    }
}
