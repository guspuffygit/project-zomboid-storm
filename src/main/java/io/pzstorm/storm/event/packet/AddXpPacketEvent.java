package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.AddXpPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.AddXpPacket} is processed on the
 * server.
 */
public class AddXpPacketEvent extends PacketEvent {

    public AddXpPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AddXpPacket getPacket() {
        return (AddXpPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AddXpPacketEvent";
    }
}
