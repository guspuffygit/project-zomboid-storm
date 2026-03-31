package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.AddUserlogPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.AddUserlogPacket} is processed on the
 * server.
 */
public class AddUserlogPacketEvent extends PacketEvent {

    public AddUserlogPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AddUserlogPacket getPacket() {
        return (AddUserlogPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AddUserlogPacketEvent";
    }
}
