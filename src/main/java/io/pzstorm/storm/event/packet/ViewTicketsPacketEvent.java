package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.ViewTicketsPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.ViewTicketsPacket} is processed on the
 * server.
 */
public class ViewTicketsPacketEvent extends PacketEvent {

    public ViewTicketsPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ViewTicketsPacket getPacket() {
        return (ViewTicketsPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ViewTicketsPacketEvent";
    }
}
