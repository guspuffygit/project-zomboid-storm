package io.pzstorm.storm.event.packet;

import zombie.core.raknet.UdpConnection;
import zombie.network.packets.service.ServerLOSPacket;

/**
 * Typed event dispatched when {@link zombie.network.packets.service.ServerLOSPacket} is processed
 * on the server.
 */
public class ServerLOSPacketEvent extends PacketEvent {

    public ServerLOSPacketEvent(Object packet, UdpConnection connection) {
        super(packet, connection);
    }

    public ServerLOSPacket getPacket() {
        return (ServerLOSPacket) getRawPacket();
    }

    @Override
    public String getName() {
        return "ServerLOSPacketEvent";
    }
}
