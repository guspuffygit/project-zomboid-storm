package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.ExtraInfoPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.ExtraInfoPacket} is processed on the
 * server.
 */
public class ExtraInfoPacketEvent extends PacketEvent {

    public ExtraInfoPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ExtraInfoPacket getPacket() {
        return (ExtraInfoPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ExtraInfoPacketEvent";
    }
}
