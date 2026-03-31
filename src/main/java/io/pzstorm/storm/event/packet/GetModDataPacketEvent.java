package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.GetModDataPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.GetModDataPacket} is processed on the
 * server.
 */
public class GetModDataPacketEvent extends PacketEvent {

    public GetModDataPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public GetModDataPacket getPacket() {
        return (GetModDataPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "GetModDataPacketEvent";
    }
}
