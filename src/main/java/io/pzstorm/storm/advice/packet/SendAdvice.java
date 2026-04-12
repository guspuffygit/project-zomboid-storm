package io.pzstorm.storm.advice.packet;

import io.pzstorm.storm.patch.fixes.NtaDebugLog;
import net.bytebuddy.asm.Advice;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;

/**
 * Advice for {@code PacketTypes.PacketType.send()}. Logs every packet being sent, capturing
 * outgoing packets in both directions.
 */
public class SendAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This PacketTypes.PacketType self, @Advice.Argument(0) IConnection connection) {
        long steamId = connection.getSteamId();
        if (!NtaDebugLog.isAllowedConnection(connection)) {
            return;
        }

        String side = GameServer.server ? "SERVER" : "CLIENT";
        String target = "";
        if (GameServer.server && connection instanceof UdpConnection udp) {
            String user = udp.getUserName() != null ? udp.getUserName() : "?";
            target = " to=" + user + " steamId=" + steamId;
        }

        String desc = "";
        try {
            INetworkPacket packet = connection.getPacket(self);
            if (packet != null) {
                desc = " desc=" + packet.getDescription();
            }
        } catch (Exception e) {
            // description is optional, ignore errors
        }

        NtaDebugLog.log(side, "PACKET-SEND type=" + self.name() + target + desc);
    }
}
