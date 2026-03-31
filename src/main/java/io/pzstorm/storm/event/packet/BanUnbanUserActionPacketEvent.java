package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.BanUnbanUserActionPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.BanUnbanUserActionPacket} is processed
 * on the server.
 */
public class BanUnbanUserActionPacketEvent extends PacketEvent {

    public BanUnbanUserActionPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public BanUnbanUserActionPacket getPacket() {
        return (BanUnbanUserActionPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "BanUnbanUserActionPacketEvent";
    }
}
