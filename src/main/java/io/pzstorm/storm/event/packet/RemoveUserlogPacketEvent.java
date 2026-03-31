package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RemoveUserlogPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RemoveUserlogPacket} is processed on
 * the server.
 */
public class RemoveUserlogPacketEvent extends PacketEvent {

    public RemoveUserlogPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RemoveUserlogPacket getPacket() {
        return (RemoveUserlogPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RemoveUserlogPacketEvent";
    }
}
