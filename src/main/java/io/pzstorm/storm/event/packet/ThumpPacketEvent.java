package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.character.ThumpPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.character.ThumpPacket} is processed on
 * the server.
 */
public class ThumpPacketEvent extends PacketEvent {

    public ThumpPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ThumpPacket getPacket() {
        return (ThumpPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ThumpPacketEvent";
    }
}
