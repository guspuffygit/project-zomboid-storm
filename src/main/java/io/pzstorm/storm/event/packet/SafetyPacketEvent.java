package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.SafetyPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.SafetyPacket} is processed on the
 * server.
 */
public class SafetyPacketEvent extends PacketEvent {

    public SafetyPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public SafetyPacket getPacket() {
        return (SafetyPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "SafetyPacketEvent";
    }
}
