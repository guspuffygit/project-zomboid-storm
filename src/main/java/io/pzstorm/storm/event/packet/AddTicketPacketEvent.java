package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.AddTicketPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.AddTicketPacket} is processed on the
 * server.
 */
public class AddTicketPacketEvent extends PacketEvent {

    public AddTicketPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public AddTicketPacket getPacket() {
        return (AddTicketPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "AddTicketPacketEvent";
    }
}
