package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.connection.LoadPlayerProfilePacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.connection.LoadPlayerProfilePacket} is
 * processed on the server.
 */
public class LoadPlayerProfilePacketEvent extends PacketEvent {

    public LoadPlayerProfilePacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public LoadPlayerProfilePacket getPacket() {
        return (LoadPlayerProfilePacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "LoadPlayerProfilePacketEvent";
    }
}
