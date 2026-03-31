package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.HumanVisualPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.HumanVisualPacket} is processed on the
 * server.
 */
public class HumanVisualPacketEvent extends PacketEvent {

    public HumanVisualPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public HumanVisualPacket getPacket() {
        return (HumanVisualPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "HumanVisualPacketEvent";
    }
}
