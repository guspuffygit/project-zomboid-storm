package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.ReadAnnotedMapPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.ReadAnnotedMapPacket} is processed on
 * the server.
 */
public class ReadAnnotedMapPacketEvent extends PacketEvent {

    public ReadAnnotedMapPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ReadAnnotedMapPacket getPacket() {
        return (ReadAnnotedMapPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ReadAnnotedMapPacketEvent";
    }
}
