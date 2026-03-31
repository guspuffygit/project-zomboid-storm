package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.ViewedTicketPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.ViewedTicketPacket} is processed on the
 * server.
 */
public class ViewedTicketPacketEvent extends PacketEvent {

    public ViewedTicketPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ViewedTicketPacket getPacket() {
        return (ViewedTicketPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ViewedTicketPacketEvent";
    }
}
