package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.HiddenAuthorsPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.HiddenAuthorsPacket} is processed on
 * the server.
 */
public class HiddenAuthorsPacketEvent extends PacketEvent {

    public HiddenAuthorsPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public HiddenAuthorsPacket getPacket() {
        return (HiddenAuthorsPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "HiddenAuthorsPacketEvent";
    }
}
