package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.RemoveTicketPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.RemoveTicketPacket} is processed on the
 * server.
 */
public class RemoveTicketPacketEvent extends PacketEvent {

    public RemoveTicketPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public RemoveTicketPacket getPacket() {
        return (RemoveTicketPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "RemoveTicketPacketEvent";
    }
}
