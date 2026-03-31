package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RolesEditPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RolesEditPacket} is processed on the
 * server.
 */
public class RolesEditPacketEvent extends PacketEvent {

    public RolesEditPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RolesEditPacket getPacket() {
        return (RolesEditPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RolesEditPacketEvent";
    }
}
