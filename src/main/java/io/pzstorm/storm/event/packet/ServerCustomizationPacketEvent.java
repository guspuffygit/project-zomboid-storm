package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.connection.ServerCustomizationPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.connection.ServerCustomizationPacket}
 * is processed on the server.
 */
public class ServerCustomizationPacketEvent extends PacketEvent {

    public ServerCustomizationPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ServerCustomizationPacket getPacket() {
        return (ServerCustomizationPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ServerCustomizationPacketEvent";
    }
}
