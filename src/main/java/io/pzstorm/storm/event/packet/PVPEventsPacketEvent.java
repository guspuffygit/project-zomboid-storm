package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.PVPEventsPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.PVPEventsPacket} is processed on the
 * server.
 */
public class PVPEventsPacketEvent extends PacketEvent {

    public PVPEventsPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public PVPEventsPacket getPacket() {
        return (PVPEventsPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "PVPEventsPacketEvent";
    }
}
